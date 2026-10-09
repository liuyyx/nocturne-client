package dev.nocturne.agent.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在目标 {@code void} 方法开头插入「占用输入时直接返回」：
 *
 * <pre>
 *   invokestatic  &lt;hookOwner&gt;.&lt;hookMethod&gt;()Z
 *   ifeq          continue
 *   return
 *   continue: ...
 * </pre>
 *
 * <p>与 {@link CallbackHookTransformer} 的分工：那个只把形参交出去、**不改变控制流**（所以能一直用
 * {@code COMPUTE_MAXS}）；这个要**吃掉**游戏自己的输入处理。我们的界面不是 vanilla {@code Screen}，
 * 不短路的话同一次点击会同时打在后面的原生界面上（点模块顺带按到「回到游戏」），按键同理。
 *
 * <p><b>栈帧</b>：插入分支必须给出分支目标的栈帧（Java 7+ 强制）。这里不换用
 * {@code COMPUTE_FRAMES}：那会重算整方法的帧、依赖类层次解析，在隔离类加载器里容易出岔子。分支
 * 目标就在方法入口，局部变量恰好是形参（长/双占两个槽但帧里只列一项），因此按描述符**手写一个
 * 满帧**即可，既不碰类层次也不用重算。
 *
 * <p><b>返回值</b>：{@code void} 直接 {@code return}；有返回值时按类型压入「已处理」的默认值——
 * 布尔返回 {@code true}（"这次输入我吃掉了"），其它基本类型返回零值、引用类型返回 {@code null}。
 * 只看返回值不看语义的话，布尔返回 {@code false} 会被调用方当成"没处理"，反而触发别的分支
 * （例如点击穿透到"点外面关界面"）。
 *
 * <p>类名/方法名/描述符三者不精确命中时一律返回 {@code null}（JVM 沿用原字节码）。
 */
public final class InputBlockTransformer implements ClassFileTransformer {

    /** ASM API 版本；需与依赖的 ASM 一致。 */
    private static final int ASM_API = Opcodes.ASM9;
    /** 钩子方法的描述符：无参、返回布尔。 */
    private static final String HOOK_DESCRIPTOR = "()Z";

    /** 内部错误是否已打印过（限流）。 */
    private final AtomicBoolean errorLogged = new AtomicBoolean(false);

    /** 目标类内部名（斜杠形式）。 */
    private final String targetClassInternalName;
    /** 目标方法名。 */
    private final String targetMethod;
    /** 目标方法描述符。 */
    private final String targetDescriptor;
    /** 钩子类内部名（斜杠形式）。 */
    private final String hookOwner;
    /** 钩子方法名。 */
    private final String hookMethod;
    /** 是否真的插桩成功（诊断用）。 */
    private final AtomicBoolean patched = new AtomicBoolean(false);

    /**
     * @param targetClass  目标类（点号或斜杠形式，构造器内归一化）
     * @param method       目标方法名
     * @param descriptor   目标方法描述符（返回类型必须是 {@code V}）
     * @param hookOwner    钩子类内部名（斜杠形式）
     * @param hookMethod   钩子方法名（无参、返回 {@code boolean}）
     */
    public InputBlockTransformer(String targetClass, String method, String descriptor,
                                 String hookOwner, String hookMethod) {
        this.targetClassInternalName = targetClass.replace('.', '/');
        this.targetMethod = method;
        this.targetDescriptor = descriptor;
        this.hookOwner = hookOwner;
        this.hookMethod = hookMethod;
    }

    /** @return 目标方法是否已被打上补丁（供注册方判断"织入了但没有目标"这类情况）。 */
    public boolean patched() {
        return patched.get();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !className.equals(targetClassInternalName)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

            ClassVisitor visitor = new ClassVisitor(ASM_API, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate =
                            super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!targetMethod.equals(name) || !targetDescriptor.equals(descriptor)) {
                        return delegate;
                    }
                    return new MethodVisitor(ASM_API, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            inject();
                            patched.set(true);
                        }

                        /** 插入 {@code if (hook()) return <handled>;} 并补上分支目标的满帧。 */
                        private void inject() {
                            Label continueLabel = new Label();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookMethod,
                                    HOOK_DESCRIPTOR, false);
                            super.visitJumpInsn(Opcodes.IFEQ, continueLabel);
                            pushHandledReturn();
                            super.visitLabel(continueLabel);
                            Object[] locals = localsOf(access);
                            super.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, null);
                        }

                        /** 压入「已处理」返回值并返回：布尔用 {@code true}，其它按类型给零值。 */
                        private void pushHandledReturn() {
                            switch (Type.getReturnType(targetDescriptor).getSort()) {
                                case Type.VOID:
                                    super.visitInsn(Opcodes.RETURN);
                                    return;
                                case Type.BOOLEAN:
                                    super.visitInsn(Opcodes.ICONST_1);
                                    super.visitInsn(Opcodes.IRETURN);
                                    return;
                                case Type.BYTE:
                                case Type.CHAR:
                                case Type.SHORT:
                                case Type.INT:
                                    super.visitInsn(Opcodes.ICONST_0);
                                    super.visitInsn(Opcodes.IRETURN);
                                    return;
                                case Type.LONG:
                                    super.visitInsn(Opcodes.LCONST_0);
                                    super.visitInsn(Opcodes.LRETURN);
                                    return;
                                case Type.FLOAT:
                                    super.visitInsn(Opcodes.FCONST_0);
                                    super.visitInsn(Opcodes.FRETURN);
                                    return;
                                case Type.DOUBLE:
                                    super.visitInsn(Opcodes.DCONST_0);
                                    super.visitInsn(Opcodes.DRETURN);
                                    return;
                                default:
                                    super.visitInsn(Opcodes.ACONST_NULL);
                                    super.visitInsn(Opcodes.ARETURN);
                            }
                        }

                        /**
                         * 方法入口处的局部变量表：实例方法首项是 {@code this}（目标类本身），
                         * 其余为形参。
                         *
                         * @param access 目标方法的访问标志
                         * @return 满帧用的局部变量项（长/双只占一项）
                         */
                        private Object[] localsOf(int access) {
                            Type[] arguments = Type.getArgumentTypes(targetDescriptor);
                            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                            Object[] locals = new Object[arguments.length + (isStatic ? 0 : 1)];
                            int at = 0;
                            if (!isStatic) {
                                locals[at++] = targetClassInternalName;
                            }
                            for (Type argument : arguments) {
                                locals[at++] = frameEntry(argument);
                            }
                            return locals;
                        }

                        /**
                         * 形参在帧里的表示：引用类型用内部名/数组描述符，基本类型用 ASM 的常量。
                         *
                         * @param type 形参类型
                         * @return 帧项
                         */
                        private Object frameEntry(Type type) {
                            switch (type.getSort()) {
                                case Type.BOOLEAN:
                                case Type.CHAR:
                                case Type.BYTE:
                                case Type.SHORT:
                                case Type.INT:
                                    return Opcodes.INTEGER;
                                case Type.FLOAT:
                                    return Opcodes.FLOAT;
                                case Type.LONG:
                                    return Opcodes.LONG;
                                case Type.DOUBLE:
                                    return Opcodes.DOUBLE;
                                case Type.ARRAY:
                                    return type.getDescriptor();
                                default:
                                    return type.getInternalName();
                            }
                        }
                    };
                }
            };
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            // 一个字都没改就原样放行：返回重写过的字节码会让 JVM 做一次无意义的重定义。
            return patched.get() ? writer.toByteArray() : null;
        } catch (Throwable t) {
            if (errorLogged.compareAndSet(false, true)) {
                System.out.println("[nocturne] input block transformer failed on "
                        + targetClassInternalName + "." + targetMethod + ": " + t);
            }
            return null;   // 绝不因为插桩让游戏类加载失败
        }
    }
}
