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

/** 用前两个引用形参决定是否让 boolean 方法继续；false 时返回 false。 */
public final class FirstArgumentGateTransformer implements ClassFileTransformer {
    private final String targetClass;
    private final String method;
    private final String descriptor;
    private final String hookOwner;
    private final String hookMethod;

    public FirstArgumentGateTransformer(String targetClass, String method, String descriptor,
                                        String hookOwner, String hookMethod) {
        if (Type.getReturnType(descriptor).getSort() != Type.BOOLEAN) {
            throw new IllegalArgumentException("target must return boolean");
        }
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (arguments.length < 2 || arguments[0].getSort() != Type.OBJECT
                || arguments[1].getSort() != Type.OBJECT) {
            throw new IllegalArgumentException("first two arguments must be objects");
        }
        this.targetClass = targetClass.replace('.', '/');
        this.method = method;
        this.descriptor = descriptor;
        this.hookOwner = hookOwner.replace('.', '/');
        this.hookMethod = hookMethod;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!targetClass.equals(className) || TransformGuard.containsHook(classfileBuffer, hookOwner, hookMethod)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
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
                            super.visitVarInsn(Opcodes.ALOAD, slot + 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookMethod,
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                            Label proceed = new Label();
                            super.visitJumpInsn(Opcodes.IFNE, proceed);
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitInsn(Opcodes.IRETURN);
                            super.visitLabel(proceed);
                            Object[] locals = locals(access);
                            super.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, null);
                        }
                    };
                }

                private Object[] locals(int access) {
                    Type[] arguments = Type.getArgumentTypes(descriptor);
                    Object[] out = new Object[arguments.length + ((access & Opcodes.ACC_STATIC) == 0 ? 1 : 0)];
                    int at = 0;
                    if ((access & Opcodes.ACC_STATIC) == 0) {
                        out[at++] = targetClass;
                    }
                    for (Type argument : arguments) {
                        out[at++] = frame(argument);
                    }
                    return out;
                }

                private Object frame(Type type) {
                    switch (type.getSort()) {
                        case Type.BOOLEAN:
                        case Type.BYTE:
                        case Type.CHAR:
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
            }, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.out.println("[nocturne] first-argument gate failed on "
                    + targetClass + "." + method + ": " + t);
            return null;
        }
    }
}
