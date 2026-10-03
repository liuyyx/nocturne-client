package dev.noturne.agent.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Inserts a static call at the start of one game method — the frame hook.
 *
 * <p>Transformation is surgical: only the named class is touched, only the named method, and only
 * its first instruction. Anything unexpected (class not matching, method absent) returns
 * {@code null}, which tells the JVM "leave this class alone".
 *
 * <p>{@code COMPUTE_MAXS} is sufficient because a balanced {@code INVOKESTATIC ()V} at the top of a
 * method neither changes the stack depth nor invalidates the existing stack map frames.
 */
public final class FrameHookTransformer implements ClassFileTransformer {

    private static final int ASM_API = Opcodes.ASM9;

    private final String targetClassInternalName;
    private final String targetMethod;
    private final String targetDescriptor;
    private final String hookOwner;
    private final String hookMethod;
    private final String hookDescriptor;

    public FrameHookTransformer(String targetClassInternalName, String targetMethod,
                                String targetDescriptor, String hookOwner, String hookMethod) {
        this.targetClassInternalName = targetClassInternalName;
        this.targetMethod = targetMethod;
        this.targetDescriptor = targetDescriptor;
        this.hookOwner = hookOwner;
        this.hookMethod = hookMethod;
        this.hookDescriptor = "()V";
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !className.equals(targetClassInternalName)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            final boolean[] patched = {false};

            ClassVisitor visitor = new ClassVisitor(ASM_API, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!targetMethod.equals(name) || !targetDescriptor.equals(descriptor)) {
                        return delegate;
                    }
                    return new MethodVisitor(ASM_API, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookMethod,
                                    hookDescriptor, false);
                            patched[0] = true;
                        }
                    };
                }
            };
            reader.accept(visitor, ClassReader.SKIP_FRAMES);
            return patched[0] ? writer.toByteArray() : null;
        } catch (Throwable t) {
            return null; // never break class loading because of us
        }
    }
}
