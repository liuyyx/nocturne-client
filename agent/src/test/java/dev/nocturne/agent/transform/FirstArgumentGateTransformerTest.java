package dev.nocturne.agent.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** XRay 首双参门控的 JVM verifier 与行为测试。 */
class FirstArgumentGateTransformerTest {
    private static final String TARGET = "dev/nocturne/agent/transform/FirstGateSubject";
    private static final String SINK = Sink.class.getName().replace('.', '/');

    public static final class Sink {
        static boolean allow;
        static int bodies;
        public static boolean gate(Object state, Object pos) {
            return allow;
        }
        public static void body() {
            bodies++;
        }
    }

    @Test
    void gatePreservesOriginalStackMapFrames() throws Exception {
        byte[] patched = new FirstArgumentGateTransformer(TARGET, "render", DESCRIPTOR, SINK, "gate")
                .transform(getClass().getClassLoader(), TARGET, null, null, subject());
        Class<?> type = define(patched);
        Object instance = type.getDeclaredConstructor().newInstance();
        Sink.allow = true;
        assertTrue((Boolean) type.getMethod("render", Object.class, Object.class)
                .invoke(instance, new Object(), new Object()));
        Sink.allow = false;
        assertFalse((Boolean) type.getMethod("render", Object.class, Object.class)
                .invoke(instance, new Object(), new Object()));
    }

    @Test
    void voidGateCancelsWithoutLeavingAValueOnTheStack() throws Exception {
        byte[] patched = new FirstArgumentGateTransformer(TARGET, "render", VOID_DESCRIPTOR, SINK, "gate")
                .transform(getClass().getClassLoader(), TARGET, null, null, voidSubject());
        Class<?> type = define(patched);
        Object instance = type.getDeclaredConstructor().newInstance();
        Sink.bodies = 0;
        Sink.allow = true;
        type.getMethod("render", Object.class, Object.class)
                .invoke(instance, new Object(), new Object());
        assertTrue(Sink.bodies == 1);
        Sink.allow = false;
        type.getMethod("render", Object.class, Object.class)
                .invoke(instance, new Object(), new Object());
        assertTrue(Sink.bodies == 1, "cancelled void gate must not run the body");
    }

    private static final String DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String VOID_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)V";

    private static byte[] subject() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "render", DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_0);
        org.objectweb.asm.Label branch = new org.objectweb.asm.Label();
        method.visitJumpInsn(Opcodes.IFEQ, branch);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(branch);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] voidSubject() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "render", VOID_DESCRIPTOR,
                null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "body", "()V", false);
        org.objectweb.asm.Label branch = new org.objectweb.asm.Label();
        method.visitJumpInsn(Opcodes.GOTO, branch);
        method.visitLabel(branch);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Class<?> define(byte[] bytes) {
        return new ClassLoader(FirstArgumentGateTransformerTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(TARGET.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }
}
