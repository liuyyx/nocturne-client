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
 * 在一个 {@code void} 方法上插入世界空间 3D 钩子，把该方法的首个 {@code float} 形参传出去：
 * <ul>
 *   <li><b>头部</b>（方法第一条指令前）：捕获世界相机矩阵；</li>
 *   <li><b>尾部</b>（每个 {@code RETURN} 之前）：实际绘制。</li>
 * </ul>
 *
 * <p>为什么必须同一个转换器：两个独立转换器注册到同一方法时，第二个会拿到已含第一个钩子的
 * 字节再跑整条转换链，头部/尾部钩子会被重复插入（真机表现为同一帧画两遍）。这里一次访问同时
 * 插入两者，最多各一次。
 *
 * <p>为什么需要头部捕获：1.8.9 的 {@code renderWorld} 尾部模型视图已被手部渲染改成手空间
 * （带缩放），解析不出相机；头部的矩阵才是世界相机（含 F5/第三人称/viewBobbing）。
 *
 * <p>平衡性：{@code FLOAD} + {@code INVOKESTATIC (F)V} 在每个插入点都是栈平衡的；不新增分支/
 * 异常处理器，因此用 {@code COMPUTE_MAXS} + {@code reader.accept(visitor, 0)} 保留原
 * StackMapTable。任何不匹配都返回 {@code null}，让 JVM 沿用原字节码。
 */
public final class WorldRenderHookTransformer implements ClassFileTransformer {

    /** ASM API 版本，与工程依赖一致。 */
    private static final int ASM_API = Opcodes.ASM9;

    /** 内部错误只记录一次，避免转换器异常时刷屏。 */
    private static final AtomicBoolean ERROR_LOGGED = new AtomicBoolean(false);

    /** 目标类内部名（斜杠形式）。 */
    private final String targetClassInternalName;
    /** 目标方法名。 */
    private final String targetMethod;
    /** 目标方法描述符，必须精确匹配。 */
    private final String targetDescriptor;
    /** 钩子类内部名（斜杠形式）。 */
    private final String hookOwner;
    /** 方法头钩子名（{@code (F)V}）；{@code null} 表示不插头部。 */
    private final String headHookMethod;
    /** 每个 RETURN 前钩子名（{@code (F)V}）；{@code null} 表示不插尾部。 */
    private final String tailHookMethod;

    /** 单钩子（尾部）兼容构造器。 */
    public WorldRenderHookTransformer(String targetClassInternalName, String targetMethod,
                                      String targetDescriptor, String hookOwner,
                                      String hookMethod) {
        this(targetClassInternalName, targetMethod, targetDescriptor, hookOwner, null, hookMethod);
    }

    /** 单钩子构造器：{@code atMethodEnd=false} 时插头部。 */
    public WorldRenderHookTransformer(String targetClassInternalName, String targetMethod,
                                      String targetDescriptor, String hookOwner,
                                      String hookMethod, boolean atMethodEnd) {
        this(targetClassInternalName, targetMethod, targetDescriptor, hookOwner,
                atMethodEnd ? null : hookMethod, atMethodEnd ? hookMethod : null);
    }

    /**
     * 双钩子构造器。
     *
     * @param headHookMethod 方法头钩子（可为 null）
     * @param tailHookMethod 每个 RETURN 前钩子（可为 null）
     */
    public WorldRenderHookTransformer(String targetClassInternalName, String targetMethod,
                                      String targetDescriptor, String hookOwner,
                                      String headHookMethod, String tailHookMethod) {
        this.targetClassInternalName = internalize(targetClassInternalName);
        this.targetMethod = targetMethod;
        this.targetDescriptor = targetDescriptor;
        this.hookOwner = internalize(hookOwner);
        this.headHookMethod = headHookMethod;
        this.tailHookMethod = tailHookMethod;
    }

    /** 把点号全限定名归一化为斜杠内部名；null 原样返回。 */
    private static String internalize(String name) {
        return name == null ? null : name.replace('.', '/');
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classfileBuffer == null || className == null
                || !className.equals(targetClassInternalName)
                || (headHookMethod == null && tailHookMethod == null)) {
            return null;
        }
        try {
            // 幂等：同一类的转换器链在注册第二个钩子（setupCameraTransform 与 renderWorld
            // 同属 bfk）时会把整条链重放到已织入的字节上。这里按钩子名先检测，已存在的
            // 头/尾钩子不再插入——否则同一帧会画两遍（真机两个 cube 偏移即此）。
            final boolean needHead = headHookMethod != null
                    && !TransformGuard.containsHook(classfileBuffer, hookOwner, headHookMethod);
            final boolean needTail = tailHookMethod != null
                    && !TransformGuard.containsHook(classfileBuffer, hookOwner, tailHookMethod);
            if (!needHead && !needTail) {
                return null;
            }
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            final boolean[] changed = new boolean[1];
            ClassVisitor visitor = new ClassVisitor(ASM_API, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor,
                            signature, exceptions);
                    if (!targetMethod.equals(name) || !targetDescriptor.equals(descriptor)) {
                        return delegate;
                    }
                    if (Type.getReturnType(descriptor).getSort() != Type.VOID) {
                        return delegate;
                    }
                    final int floatSlot = firstFloatSlot(access, descriptor);
                    if (floatSlot < 0) {
                        return delegate;
                    }
                    changed[0] = true;
                    return new MethodVisitor(ASM_API, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (needHead) {
                                emitHook(floatSlot, headHookMethod);
                            }
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN && needTail) {
                                emitHook(floatSlot, tailHookMethod);
                            }
                            super.visitInsn(opcode);
                        }

                        private void emitHook(int slot, String hook) {
                            super.visitVarInsn(Opcodes.FLOAD, slot);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hook, "(F)V",
                                    false);
                        }
                    };
                }
            };
            reader.accept(visitor, 0);
            return changed[0] ? writer.toByteArray() : null;
        } catch (Throwable t) {
            if (!ERROR_LOGGED.getAndSet(true)) {
                System.out.println("[nocturne] world render hook transform failed for "
                        + targetClassInternalName + "." + targetMethod + ": " + t);
            }
            return null;
        }
    }

    /**
     * 计算方法首个 {@code float} 形参的局部变量槽。
     *
     * @param access     方法访问标志（决定是否从槽 1 开始）
     * @param descriptor 方法描述符
     * @return 槽位；没有 float 形参时返回 -1
     */
    private static int firstFloatSlot(int access, String descriptor) {
        int slot = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        Type[] arguments = Type.getArgumentTypes(descriptor);
        for (int i = 0; i < arguments.length; i++) {
            Type type = arguments[i];
            if (type.getSort() == Type.FLOAT) {
                return slot;
            }
            slot += type.getSize();
        }
        return -1;
    }
}
