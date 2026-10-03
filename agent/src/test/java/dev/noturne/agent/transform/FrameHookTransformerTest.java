package dev.noturne.agent.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FrameHookTransformerTest {

    private static final String HOOK_OWNER = "dev/noturne/client/runtime/NoturneRuntime";

    /** Builds a minimal class with one static no-arg method, standing in for the game class. */
    private static byte[] sampleClass(String internalName, String methodName, String descriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int hookCallCount(byte[] bytecode) {
        final int[] count = {0};
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        if (HOOK_OWNER.equals(owner) && "onFrame".equals(name)) {
                            count[0]++;
                        }
                    }
                };
            }
        }, 0);
        return count[0];
    }

    @Test
    void insertsTheHookAtTheStartOfTheTargetMethod() {
        FrameHookTransformer transformer =
                new FrameHookTransformer("ave", "av", "()V", HOOK_OWNER, "onFrame");

        byte[] output = transformer.transform(null, "ave", null, null, sampleClass("ave", "av", "()V"));

        assertNotNull(output, "target method must be patched");
        assertEquals(1, hookCallCount(output));
    }

    @Test
    void leavesOtherClassesUntouched() {
        FrameHookTransformer transformer =
                new FrameHookTransformer("ave", "av", "()V", HOOK_OWNER, "onFrame");
        assertNull(transformer.transform(null, "other/Thing", null, null,
                sampleClass("other/Thing", "av", "()V")));
    }

    @Test
    void leavesOtherMethodsUntouched() {
        FrameHookTransformer transformer =
                new FrameHookTransformer("ave", "av", "()V", HOOK_OWNER, "onFrame");
        assertNull(transformer.transform(null, "ave", null, null,
                sampleClass("ave", "somethingElse", "()V")));
    }

    @Test
    void requiresTheExactDescriptor() {
        FrameHookTransformer transformer =
                new FrameHookTransformer("ave", "av", "(I)V", HOOK_OWNER, "onFrame");
        assertNull(transformer.transform(null, "ave", null, null, sampleClass("ave", "av", "()V")));
    }

    @Test
    void toleratesGarbageInput() {
        FrameHookTransformer transformer =
                new FrameHookTransformer("ave", "av", "()V", HOOK_OWNER, "onFrame");
        assertNull(transformer.transform(null, "ave", null, null, new byte[]{1, 2, 3}));
    }
}
