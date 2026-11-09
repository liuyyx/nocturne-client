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

/**
 * 用实体渲染的**首个引用形参**门控目标方法；false 时直接结束方法。
 *
 * <p>返回值形态随版本不同：
 * <ul>
 *   <li>1.13+ 的 {@code EntityRenderDispatcher#render(...,I)V} 返回 void → 插入 {@code RETURN}；</li>
 *   <li>1.8.9 的 {@code RenderManager#doRenderEntity(Entity,float,boolean)Z} 返回 boolean
 *       → 插入 {@code ICONST_0; IRETURN}。旧实现强制 void，构造期抛
 *       {@code IllegalArgumentException}，被反射包成 {@code InvocationTargetException} 后整条
 *       实体门控（NoRender / YSM）在 1.8.9 上静默禁用（真机 12 次 entity gate disabled）。</li>
 * </ul>
 */
public final class EntityRenderGateTransformer implements ClassFileTransformer {
    private final String targetClass;
    private final String method;
    private final String descriptor;
    private final String hookOwner;
    /** 目标方法是否返回 boolean；false 表示 void。 */
    private final boolean booleanResult;

    public EntityRenderGateTransformer(String targetClass, String method, String descriptor, String hookOwner) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        int returnSort = Type.getReturnType(descriptor).getSort();
        if ((returnSort != Type.VOID && returnSort != Type.BOOLEAN) || arguments.length == 0
                || arguments[0].getSort() != Type.OBJECT) {
            throw new IllegalArgumentException(
                    "entity render target must return void/boolean with object first argument");
        }
        this.targetClass = targetClass.replace('.', '/');
        this.method = method;
        this.descriptor = descriptor;
        this.hookOwner = hookOwner.replace('.', '/');
        this.booleanResult = returnSort == Type.BOOLEAN;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> beingRedefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!targetClass.equals(className)
                || TransformGuard.containsHookInMethod(bytes, targetClass, method, descriptor,
                        hookOwner, "entityRenderGate")) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            final int classVersion = reader.readUnsignedShort(6);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                    if (!method.equals(name) || !descriptor.equals(desc)) {
                        return delegate;
                    }
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            int slot = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
                            super.visitVarInsn(Opcodes.ALOAD, slot);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, "entityRenderGate",
                                    "(Ljava/lang/Object;)Z", false);
                            Label proceed = new Label();
                            super.visitJumpInsn(Opcodes.IFNE, proceed);
                            if (booleanResult) {
                                super.visitInsn(Opcodes.ICONST_0);
                                super.visitInsn(Opcodes.IRETURN);
                            } else {
                                super.visitInsn(Opcodes.RETURN);
                            }
                            super.visitLabel(proceed);
                            if (classVersion >= Opcodes.V1_6) {
                                Object[] locals = frameLocals(access);
                                // 方法头只插一次 F_NEW；原始帧已全部展开（EXPAND_FRAMES），
                                // 与原方法在 offset 0 的帧不会同址冲突。
                                super.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, null);
                            }
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.out.println("[nocturne] entity render gate failed: " + failure);
            return null;
        }
    }

    private Object[] frameLocals(int access) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        Object[] locals = new Object[arguments.length + (isStatic ? 0 : 1)];
        int at = 0;
        if (!isStatic) {
            locals[at++] = targetClass;
        }
        for (Type argument : arguments) {
            switch (argument.getSort()) {
                case Type.BOOLEAN:
                case Type.CHAR:
                case Type.BYTE:
                case Type.SHORT:
                case Type.INT:
                    locals[at++] = Opcodes.INTEGER;
                    break;
                case Type.FLOAT:
                    locals[at++] = Opcodes.FLOAT;
                    break;
                case Type.LONG:
                    locals[at++] = Opcodes.LONG;
                    break;
                case Type.DOUBLE:
                    locals[at++] = Opcodes.DOUBLE;
                    break;
                case Type.ARRAY:
                    locals[at++] = argument.getDescriptor();
                    break;
                default:
                    locals[at++] = argument.getInternalName();
            }
        }
        return locals;
    }
}
