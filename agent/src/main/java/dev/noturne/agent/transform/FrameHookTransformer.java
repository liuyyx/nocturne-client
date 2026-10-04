package dev.noturne.agent.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * 计算帧信息。原有的 StackMapTable 会被原样保留（帧偏移由 ASM 自动修正），从而在 Java 6+ 的
 * 严格校验下依然能通过——这一点对 LWJGL3（Java 8+ 字节码）的 {@code glfwSwapBuffers} 与
 * {@code SDL_GL_SwapWindow} 目标尤为关键。
 */
public final class FrameHookTransformer implements ClassFileTransformer {

    /** ASM API 版本；需与工程依赖的 ASM 版本一致，否则过新的 class 文件版本会解析失败。 */
    private static final int ASM_API = Opcodes.ASM9;

    /**
     * 内部错误日志限流开关。
     *
     * <p>「类名/方法未命中」是正常结果（静默返回 {@code null}），但「字节码解析失败」或
     * 「ASM 版本不兼容」是把钩子整个装不上的真实故障，必须能从游戏日志里发现。热路径上不能
     * 每帧刷屏，因此只打印第一条。该字段由加载本转换器的 {@code EmbeddedAsmLoader} 子加载器隔离，
     * 每个子加载器各有一份。
     */
    private static final AtomicBoolean ERROR_LOGGED = new AtomicBoolean(false);

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
        // JVM 交给 transformer 的 className 一律是斜杠内部名（如 org/lwjgl/opengl/Display），
        // 而调用点很容易顺手写成点号全限定名。这里统一归一化为斜杠形式，否则两者永不相等、
        // 钩子静默装不上（ADHOC-ClassnameForm）。
        this.targetClassInternalName = internalize(targetClassInternalName);
        this.targetMethod = targetMethod;
        this.targetDescriptor = targetDescriptor;
        this.hookOwner = internalize(hookOwner);
        this.hookMethod = hookMethod;
        this.hookDescriptor = "()V";
    }

    /** 把点号全限定名归一化为斜杠内部名；null 原样返回（后续匹配会因 null 而安全跳过）。 */
    private static String internalize(String name) {
        return name == null ? null : name.replace('.', '/');
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
            // 必须**保留**原有 StackMapTable（不用 SKIP_FRAMES）：Java 6+ 的 class 文件对含分支的方法
            // 强制要求栈映射帧，而帧偏移会随插入的指令自动重算（ASM 用标签追踪）。若把帧丢弃，
            // 现代 JVM（GLFW/SDL 的 LWJGL3 类即为 Java 8+ 字节码）会在校验期抛 VerifyError，
            // 表现为「注入成功但钩子不生效」。插入的是栈平衡的 void 调用，不改帧结构，故与
            // COMPUTE_MAXS 兼容（COMPUTE_MAXS 不重算帧，二者互不冲突）。
            reader.accept(visitor, 0);
            // 未命中目标方法时返回 null，JVM 将使用原始字节码，行为完全不变。
            return patched[0] ? writer.toByteArray() : null;
        } catch (Throwable t) {
            // 走到这里说明类名已命中、但字节码解析失败（ASM 版本不兼容 / class 文件损坏 /
            // NoClassDefFoundError），这是真实故障而非「未命中」，必须留一条可检索日志——
            // 否则现象是「注入成功但界面永不出现」，从日志里完全无从下手（C-02 / M-95）。
            // 限流：热路径上只打印第一条，避免每帧刷屏。
            if (ERROR_LOGGED.compareAndSet(false, true)) {
                System.out.println("[noturne] frame hook transform failed for " + targetClassInternalName
                        + "." + targetMethod + ": " + t);
                t.printStackTrace();
            }
            // 绝不允许因为我们的插桩而让类加载失败：放弃本次转换，JVM 沿用原始字节码。
            return null;
        }
    }
}
