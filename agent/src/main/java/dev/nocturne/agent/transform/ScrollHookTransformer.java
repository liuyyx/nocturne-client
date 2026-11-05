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
 * 滚轮形参钩子：把目标方法开头指定的 {@code double} 形参交给静态钩子。
 *
 * <p>为什么单开一个转换器：{@link CallbackHookTransformer} 只交出<b>首个引用形参</b>，而
 * {@code MouseHandler#onScroll(JDD)V} 的三个形参是 {@code long}、{@code double}、{@code double}，
 * 它一个都接不住。这正是 GLFW 世代（1.13–1.19）界面打开后"列表滚不动"的根因——滚轮事件确实
 * 到了游戏，但没有任何人把增量交给我们。
 *
 * <p>注入位置固定为方法开头、单点一次：入口处的形参一定可用，StackMapTable 也一定有效。钩子只
 * 做一个累加，栈平衡，不需要重新计算帧。
 */
public final class ScrollHookTransformer implements ClassFileTransformer {

    /** ASM API 版本；需与依赖的 ASM 一致。 */
    private static final int ASM_API = Opcodes.ASM9;
    /** 钩子方法固定接收一个 double。 */
    private static final String HOOK_DESCRIPTOR = "(D)V";

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
    /** 要交出去的第几个形参（0 起）。 */
    private final int argumentIndex;
    /** 是否已织入（供 {@code registerInputBlock} 同款日志探查）。 */
    private final AtomicBoolean patched = new AtomicBoolean(false);
    /** 内部错误是否已打印过（限流）。 */
    private final AtomicBoolean errorLogged = new AtomicBoolean(false);

    /**
     * 构造一个滚轮形参转换器。
     *
     * @param targetClass   目标类（点号或斜杠形式）
     * @param method        目标方法名
     * @param descriptor    目标方法描述符，返回类型须为 {@code V}
     * @param hookOwner     钩子类内部名（斜杠形式）
     * @param hookMethod    钩子方法名，签名 {@code (D)V}
     * @param argumentIndex 要交出去的形参下标（0 起），该形参必须是 double
     */
    public ScrollHookTransformer(String targetClass, String method, String descriptor,
                                 String hookOwner, String hookMethod, int argumentIndex) {
        if (Type.getReturnType(descriptor).getSort() != Type.VOID) {
            throw new IllegalArgumentException("scroll target must return void: " + descriptor);
        }
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (argumentIndex < 0 || argumentIndex >= arguments.length
                || arguments[argumentIndex].getSort() != Type.DOUBLE) {
            throw new IllegalArgumentException("scroll argument must be a double: "
                    + argumentIndex + " in " + descriptor);
        }
        this.targetClassInternalName = targetClass.replace('.', '/');
        this.targetMethod = method;
        this.targetDescriptor = descriptor;
        this.hookOwner = hookOwner.replace('.', '/');
        this.hookMethod = hookMethod;
        this.argumentIndex = argumentIndex;
    }

    /** @return 是否真的织入过目标方法 */
    public boolean patched() {
        return patched.get();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !className.equals(targetClassInternalName)
                || TransformGuard.containsHook(classfileBuffer, hookOwner, hookMethod)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new ClassVisitor(ASM_API, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature,
                            exceptions);
                    if (!targetMethod.equals(name) || !targetDescriptor.equals(descriptor)) {
                        return delegate;
                    }
                    final int slot = argumentSlot(access, descriptor, argumentIndex);
                    return new MethodVisitor(ASM_API, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.DLOAD, slot);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookMethod,
                                    HOOK_DESCRIPTOR, false);
                            patched.set(true);
                        }
                    };
                }
            };
            // 原样保留 StackMapTable：只插入一条栈平衡的调用，帧内容仍然有效。
            reader.accept(visitor, 0);
            return patched.get() ? writer.toByteArray() : null;
        } catch (Throwable t) {
            if (errorLogged.compareAndSet(false, true)) {
                System.out.println("[nocturne] scroll hook failed for "
                        + targetClassInternalName + "." + targetMethod + targetDescriptor + ": " + t);
                t.printStackTrace();
            }
            return null;
        }
    }

    /** 计算第 {@code argumentIndex} 个形参的局部变量槽位。 */
    private static int argumentSlot(int access, String descriptor, int argumentIndex) {
        int slot = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        Type[] arguments = Type.getArgumentTypes(descriptor);
        for (int i = 0; i < argumentIndex; i++) {
            slot += arguments[i].getSize();
        }
        return slot;
    }
}
