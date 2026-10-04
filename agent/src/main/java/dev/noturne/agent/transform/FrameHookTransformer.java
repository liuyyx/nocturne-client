package dev.noturne.agent.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * 在游戏某个方法的首条指令前插入一次静态调用，即「帧钩子」。
 *
 * <p>插桩是外科手术式的：只动指定类、只动指定方法、且只动它的第一条指令。任何不符合预期的情况
 * （类名不匹配、方法不存在、字节码无法解析）都返回 {@code null}，含义是「请按原样使用这个类」，
 * 从而把失败风险限制在这一次转换上，绝不影响游戏自身的类加载。
 *
 * <p>之所以把帧交换点（{@code Display.update()} / {@code glfwSwapBuffers}）作为插入位置：只有
 * 在画面提交之前完成的绘制才能真正留在屏幕上。
 *
 * <p>使用 {@code COMPUTE_MAXS}（而非 {@code COMPUTE_FRAMES}）即可：在方法开头插入一条栈平衡的
 * {@code INVOKESTATIC ()V} 既不改变栈深度，也不会让已有的 StackMapTable 失效，因此无需重新
 * 计算帧信息。
 */
public final class FrameHookTransformer implements ClassFileTransformer {

    /** ASM API 版本；需与工程依赖的 ASM 版本一致，否则过新的 class 文件版本会解析失败。 */
    private static final int ASM_API = Opcodes.ASM9;

    /** 目标类的内部名（如 {@code org/lwjgl/opengl/Display}，斜杠形式）。 */
    private final String targetClassInternalName;
    /** 目标方法名。 */
    private final String targetMethod;
    /** 目标方法描述符，必须精确匹配，否则视为未命中。 */
    private final String targetDescriptor;
    /** 钩子所在类的内部名（斜杠形式）。 */
    private final String hookOwner;
    /** 钩子方法名。 */
    private final String hookMethod;
    /** 钩子方法描述符，固定为无参无返回的 {@code ()V}。 */
    private final String hookDescriptor;

    /**
     * 构造一个帧钩子转换器。
     *
     * @param targetClassInternalName 目标类内部名
     * @param targetMethod 目标方法名
     * @param targetDescriptor 目标方法描述符，需与字节码中的完全一致
     * @param hookOwner 钩子类内部名（斜杠形式）
     * @param hookMethod 钩子方法名
     */
    public FrameHookTransformer(String targetClassInternalName, String targetMethod,
                                String targetDescriptor, String hookOwner, String hookMethod) {
        this.targetClassInternalName = targetClassInternalName;
        this.targetMethod = targetMethod;
        this.targetDescriptor = targetDescriptor;
        this.hookOwner = hookOwner;
        this.hookMethod = hookMethod;
        this.hookDescriptor = "()V";
    }

    /**
     * 转换指定类，在目标方法开头插入钩子调用。
     *
     * @param loader 定义该类的类加载器（本转换未使用）
     * @param className 内部名的待转换类；初始化阶段的重复定义会传 null
     * @param classBeingRedefined 重转换/重定义时的旧类，首次加载为 null
     * @param protectionDomain 类的保护域（本转换未使用）
     * @param classfileBuffer 原始字节码
     * @return 打了补丁的字节码；未命中目标或解析失败时返回 {@code null}，表示沿用原始字节码
     */
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !className.equals(targetClassInternalName)) {
            // className 为 null 表示类在初始化中重复定义；类名不符则不是我们的目标。
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            // 传入 reader 让 COMPUTE_MAXS 能复用原始常量池，避免重建。
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            // 数组充当可变标记，供匿名内部类回写「是否真的打过补丁」。
            final boolean[] patched = {false};

            ClassVisitor visitor = new ClassVisitor(ASM_API, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    // 方法名与描述符必须同时命中，避免误改同名重载。
                    if (!targetMethod.equals(name) || !targetDescriptor.equals(descriptor)) {
                        return delegate;
                    }
                    return new MethodVisitor(ASM_API, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // visitCode 之后紧跟的就是方法第一条指令，此处插入即「方法开头」。
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookMethod,
                                    hookDescriptor, false);
                            patched[0] = true;
                        }
                    };
                }
            };
            reader.accept(visitor, ClassReader.SKIP_FRAMES);
            // SKIP_FRAMES：不读 StackMapTable——插入的调用不改变帧结构，跳过可减少开销。
            // 未命中目标方法时返回 null，JVM 将使用原始字节码，行为完全不变。
            return patched[0] ? writer.toByteArray() : null;
        } catch (Throwable t) {
            // 绝不允许因为我们的插桩而让类加载失败：吞掉异常并放弃本次转换。
            return null;
        }
    }
}
