package dev.nocturne.agent.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * 头部捕获 {@code this}、末尾无参消费。
 *
 * <p>{@code KeyboardInput#tick()V} 的 RETURN 处 StackMapTable 常把槽 0 裁成 {@code top}，
 * 末尾再 {@code aload 0} 会让 JVM 在重转换时报 {@code VerifyError: Bad local variable type}。
 * 因此头部把实例交给钩子暂存，末尾只调用无参消费钩子；多返回点重复消费是空操作。
 */
public final class InputTickHookTransformer implements ClassFileTransformer {

    private final String targetClass;
    private final String method;
    private final String descriptor;
    private final String hookOwner;
    private final String captureMethod;
    private final String deferredMethod;

    public InputTickHookTransformer(String targetClass, String method, String descriptor,
                                   String hookOwner, String captureMethod, String deferredMethod) {
        if (Type.getReturnType(descriptor).getSort() != Type.VOID) {
            throw new IllegalArgumentException("input tick target must return void: " + descriptor);
        }
        this.targetClass = targetClass.replace('.', '/');
        this.method = method;
        this.descriptor = descriptor;
        this.hookOwner = hookOwner.replace('.', '/');
        this.captureMethod = captureMethod;
        this.deferredMethod = deferredMethod;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!targetClass.equals(className)
                || TransformGuard.containsHook(classfileBuffer, hookOwner, captureMethod)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            final boolean[] patched = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                    if (!method.equals(name) || !descriptor.equals(desc)
                            || (access & Opcodes.ACC_STATIC) != 0) {
                        return delegate;
                    }
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, captureMethod,
                                    "(Ljava/lang/Object;)V", false);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner,
                                        deferredMethod, "()V", false);
                                patched[0] = true;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return patched[0] ? writer.toByteArray() : null;
        } catch (Throwable t) {
            System.out.println("[nocturne] input tick transformer failed on "
                    + targetClass + "." + method + ": " + t);
            return null;
        }
    }
}
