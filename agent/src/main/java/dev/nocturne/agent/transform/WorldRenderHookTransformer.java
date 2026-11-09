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
 * 在某个 {@code void} 方法的每个 {@code RETURN} 之前插入一次静态调用，把该方法的
 * <b>首个 {@code float} 形参</b>原样传出去。
 *
 * <p>用途：世界空间 3D 渲染钩子。1.8.9 的 {@code EntityRenderer#renderWorld(float, long)} 在世界
 * 几何画完之后、HUD 之前返回；在返回点插一次调用，深度缓冲里已经是完整的世界，相机变换也还在
 * 有效期内。相比「方法开头」插入，返回点不会让覆盖物被后续地形盖住/参与错误的深度写入。
 *
 * <p>为什么需要专门的转换器：
 * <ul>
 *   <li>{@code FrameHookTransformer} 只插无参调用，拿不到 {@code partialTicks}；</li>
 *   <li>{@code CallbackHookTransformer} 只转发首个<b>引用</b>形参，而这里是基本类型；</li>
 *   <li>形参槽位由描述符决定（实例方法从 1 开始，静态方法从 0 开始），必须按
 *       {@link Type#getArgumentTypes} 计算，不能写死。</li>
 * </ul>
 *
 * <p>平衡性：{@code FLOAD} 压入一个 float，{@code INVOKESTATIC (F)V} 消耗它，{@code RETURN} 处栈
 * 仍为空——不改变最大栈深之外的控制流，也不新增分支/异常处理器，因此用
 * {@code ClassWriter(COMPUTE_MAXS)} + {@code reader.accept(visitor, 0)} 保留原 StackMapTable 即可。
 * 任何不匹配（类名、方法名、描述符、返回类型、没有 float 形参）都返回 {@code null}，让 JVM
 * 沿用原字节码。
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
    /** 钩子方法名。 */
    private final String hookMethod;

    public WorldRenderHookTransformer(String targetClassInternalName, String targetMethod,
                                      String targetDescriptor, String hookOwner,
                                      String hookMethod) {
        this.targetClassInternalName = internalize(targetClassInternalName);
        this.targetMethod = targetMethod;
        this.targetDescriptor = targetDescriptor;
        this.hookOwner = internalize(hookOwner);
        this.hookMethod = hookMethod;
    }

    /** 把点号全限定名归一化为斜杠内部名；null 原样返回。 */
    private static String internalize(String name) {
        return name == null ? null : name.replace('.', '/');
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classfileBuffer == null || className == null
                || !className.equals(targetClassInternalName)) {
            return null;
        }
        try {
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
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.FLOAD, floatSlot);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner,
                                        hookMethod, "(F)V", false);
                            }
                            super.visitInsn(opcode);
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
