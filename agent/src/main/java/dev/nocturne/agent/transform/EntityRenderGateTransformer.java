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

/** 用实体渲染首参门控 void 方法；false 时直接进入 RETURN。 */
public final class EntityRenderGateTransformer implements ClassFileTransformer {
    private final String targetClass;
    private final String method;
    private final String descriptor;
    private final String hookOwner;

    public EntityRenderGateTransformer(String targetClass, String method, String descriptor, String hookOwner) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (Type.getReturnType(descriptor).getSort() != Type.VOID || arguments.length == 0
                || arguments[0].getSort() != Type.OBJECT) {
            throw new IllegalArgumentException("entity render target must be void with object first argument");
        }
        this.targetClass = targetClass.replace('.', '/');
        this.method = method;
        this.descriptor = descriptor;
        this.hookOwner = hookOwner.replace('.', '/');
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> beingRedefined,
                            ProtectionDomain domain, byte[] bytes) {
        if (!targetClass.equals(className)
                || TransformGuard.containsHook(bytes, hookOwner, "entityRenderGate")) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(bytes);
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
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, "entityRenderGate",
                                    "(Ljava/lang/Object;)Z", false);
                            Label proceed = new Label();
                            super.visitJumpInsn(Opcodes.IFNE, proceed);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(proceed);
                            Object[] locals = frameLocals(access);
                            super.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, null);
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
