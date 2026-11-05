package dev.nocturne.agent.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** 在每个 float 返回点调用缩放钩子；不改参数、不重建帧。 */
public final class FloatReturnHookTransformer implements ClassFileTransformer {
    private final String targetClass;
    private final String method;
    private final String descriptor;
    private final String hookOwner;
    private final String hookMethod;

    public FloatReturnHookTransformer(String targetClass, String method, String descriptor,
                                      String hookOwner, String hookMethod) {
        if (Type.getReturnType(descriptor).getSort() != Type.FLOAT) {
            throw new IllegalArgumentException("target must return float");
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
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.FRETURN) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookMethod,
                                        "(F)F", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.out.println("[nocturne] float-return hook failed on "
                    + targetClass + "." + method + ": " + t);
            return null;
        }
    }
}
