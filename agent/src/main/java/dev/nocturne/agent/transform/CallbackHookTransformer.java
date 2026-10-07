package dev.nocturne.agent.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把目标方法的**第一个引用形参**交给钩子：在方法开头插入
 * {@code aload <首个形参槽位>; invokestatic 钩子(Ljava/lang/Object;)V}。
 *
 * <p>与 {@link FrameHookTransformer} 的分工：
 * <ul>
 *   <li>帧钩子只插一条无参调用，用在「缓冲区交换」这种没有参数的入口；</li>
 *   <li>本转换器处理**绘制上下文是回调形参**的那一代 API（1.16.5 的 {@code DrawContext}、
 *       1.17+ 的 {@code GuiGraphics}、26.x 的 {@code GuiGraphicsExtractor}）——它们既不是
 *       {@code Minecraft} 的字段也不是静态方法，只能从 {@code Screen.render}/{@code Gui.render}
 *       这类方法的形参里取。抓到的对象只在那一帧有效，所以钩子必须当帧就把界面画进去。</li>
 * </ul>
 *
 * <p>失败策略与帧钩子一致：类名/方法名/描述符三者不精确命中、或首个形参不是引用类型时一律返回
 * {@code null}（JVM 沿用原字节码）；内部错误只打印一次，绝不因为插桩让游戏类加载失败。
 */
public final class CallbackHookTransformer implements ClassFileTransformer {

    /** ASM API 版本；需与依赖的 ASM 一致。 */
    private static final int ASM_API = Opcodes.ASM9;
    /** 钩子方法固定接收一个对象参数。 */
    private static final String HOOK_DESCRIPTOR = "(Ljava/lang/Object;)V";

    /** 内部错误是否已打印过（限流，避免每帧刷屏）。 */
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
    /** 注入位置：{@code false} 方法开头，{@code true} 方法末尾（首个 {@code RETURN} 之前）。 */
    private final boolean atMethodEnd;
    /** 描述串，仅用于诊断日志。 */
    private final String label;

    /**
     * 构造一个「首参回调」转换器。
     *
     * @param targetClassInternalName 目标类内部名（点号或斜杠都能接受，构造时归一化）
     * @param targetMethod            目标方法名
     * @param targetDescriptor        目标方法描述符，需与字节码完全一致
     * @param hookOwner               钩子类内部名（斜杠形式）
     * @param hookMethod              钩子方法名，签名固定为 {@code (Ljava/lang/Object;)V}
     * @param atMethodEnd             {@code true} = 在方法末尾注入（首个 {@code RETURN} 之前），
     *                                {@code false} = 在方法开头注入。绘制类入口需要末尾注入：
     *                                目标方法自己也要画东西，开头注入的内容会被它随后画的内容盖住
     */
    public CallbackHookTransformer(String targetClassInternalName, String targetMethod,
                                   String targetDescriptor, String hookOwner, String hookMethod,
                                   boolean atMethodEnd) {
        this.targetClassInternalName = internalize(targetClassInternalName);
        this.targetMethod = targetMethod;
        this.targetDescriptor = targetDescriptor;
        this.hookOwner = internalize(hookOwner);
        this.hookMethod = hookMethod;
        this.atMethodEnd = atMethodEnd;
        this.label = this.targetClassInternalName + "." + targetMethod + targetDescriptor
                + " -> " + this.hookOwner + "." + hookMethod + HOOK_DESCRIPTOR
                + (atMethodEnd ? " @end" : " @head");
    }

    /** 把点号全限定名归一化为斜杠内部名；null 原样返回。 */
    private static String internalize(String name) {
        return name == null ? null : name.replace('.', '/');
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
            final boolean[] patched = {false};

            ClassVisitor visitor = new ClassVisitor(ASM_API, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!targetMethod.equals(name) || !targetDescriptor.equals(descriptor)) {
                        return delegate;
                    }
                    final int firstArgumentSlot = firstArgumentSlot(access, descriptor);
                    if (firstArgumentSlot < 0) {
                        // 没有引用形参可交出去：不是我们的目标，按原样放行。
                        return delegate;
                    }
                    return new MethodVisitor(ASM_API, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (!atMethodEnd) {
                                inject();
                            }
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            // 末尾注入：目标方法自己先画完，钩子再把叠加层画上去（否则被它盖住）。
                            // 只在**首个** RETURN 之前插入——多返回点的方法里每个 RETURN 都插会让
                            // 钩子一帧内被调多次，叠加层重复绘制。
                            if (atMethodEnd && opcode == Opcodes.RETURN && !patched[0]) {
                                inject();
                            }
                            super.visitInsn(opcode);
                        }

                        /** 插入 {@code aload <首个引用形参>; invokestatic 钩子}；只插一次。 */
                        private void inject() {
                            super.visitVarInsn(Opcodes.ALOAD, firstArgumentSlot);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookMethod,
                                    HOOK_DESCRIPTOR, false);
                            patched[0] = true;
                        }
                    };
                }
            };
            // 保留原始 StackMapTable（SKIP_FRAMES 会让含分支的方法在 Java 6+ 校验下 VerifyError），
            // 只插入一条栈平衡的调用，帧内容仍然有效。
            reader.accept(visitor, 0);
            return patched[0] ? writer.toByteArray() : null;
        } catch (Throwable t) {
            if (errorLogged.compareAndSet(false, true)) {
                System.out.println("[nocturne] callback hook failed for " + label + ": " + t);
                t.printStackTrace();
            }
            return null;
        }
    }

    /**
     * 计算第一个形参在局部变量表中的槽位。
     *
     * <p>实例方法的槽位 0 是 {@code this}，因此首个形参从 1 开始；静态方法从 0 开始。
     * 首个形参必须是**引用类型**（我们要把它当对象交给钩子）：无参方法或首参是 long/double 时返回 -1，
     * 调用方据此判定「不命中」。
     *
     * @param access     方法访问标志
     * @param descriptor 方法描述符
     * @return 首个形参槽位；不适用时返回 -1
     */
    private static int firstArgumentSlot(int access, String descriptor) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (arguments.length == 0) {
            return -1;
        }
        Type first = arguments[0];
        if (first.getSort() == Type.LONG || first.getSort() == Type.DOUBLE) {
            return -1;
        }
        return (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
    }
}
