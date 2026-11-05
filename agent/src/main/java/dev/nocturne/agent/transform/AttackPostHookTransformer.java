package dev.nocturne.agent.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** 头部捕获 Player/Entity，返回点消费，彻底避开 RETURN 处的局部帧问题。 */
public final class AttackPostHookTransformer implements ClassFileTransformer {
    private final String targetClass;
    private final String method;
    private final String descriptor;
    private final String hookOwner;

    public AttackPostHookTransformer(String targetClass, String method, String descriptor, String hookOwner) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (Type.getReturnType(descriptor).getSort() != Type.VOID || arguments.length != 2
                || arguments[0].getSort() != Type.OBJECT || arguments[1].getSort() != Type.OBJECT) {
            throw new IllegalArgumentException("attack target must be (Player, Entity) void");
        }
        this.targetClass = targetClass.replace('.', '/');
        this.method = method;
        this.descriptor = descriptor;
        this.hookOwner = hookOwner.replace('.', '/');
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!targetClass.equals(className) || TransformGuard.containsHook(classfileBuffer, hookOwner, "attackCapture")) {
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
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, "attackCapture",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner,
                                        "attackPost", "()V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.out.println("[nocturne] attack-post transformer failed on "
                    + targetClass + "." + method + ": " + t);
            return null;
        }
    }
}
