package dev.nocturne.agent.transform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** attackPost 头部捕获、所有返回路径消费与 retransform 幂等测试。 */
class AttackPostHookTransformerTest {
    private static final String TARGET = "dev/nocturne/agent/transform/AttackPostSubject";
    private static final String SINK = Sink.class.getName().replace('.', '/');

    public static final class Sink {
        static final List<String> calls = new ArrayList<String>();
        static Object player;
        static Object target;
        static boolean allow = true;

        public static boolean attack(Object player, Object target) {
            calls.add("gate");
            return allow;
        }
        public static void attackCapture(Object capturedPlayer, Object capturedTarget) {
            player = capturedPlayer;
            target = capturedTarget;
            calls.add("capture");
        }

        public static void attackPost() {
            calls.add("post:" + (player == target));
        }

        public static void body() {
            calls.add("body");
        }
    }

    @BeforeEach
    void reset() {
        Sink.calls.clear();
        Sink.player = null;
        Sink.target = null;
        Sink.allow = true;
    }

    @Test
    void capturesAtHeadAndPostsOnEveryFramedReturn() throws Exception {
        byte[] patched = transformer().transform(getClass().getClassLoader(), TARGET, null, null, subject());
        assertNotNull(patched);
        Class<?> type = define(patched);
        Object instance = type.getDeclaredConstructor().newInstance();
        Object shared = new Object();

        type.getMethod("hit", Object.class, Object.class).invoke(instance, shared, shared);
        assertEquals(java.util.Arrays.asList("capture", "body", "post:true"), Sink.calls);

        Sink.calls.clear();
        type.getMethod("hit", Object.class, Object.class).invoke(instance, new Object(), new Object());
        assertEquals(java.util.Arrays.asList("capture", "body", "body", "post:false"), Sink.calls);
    }

    @Test
    void preGateCanStillBeWovenAfterPostCapture() throws Exception {
        byte[] post = transformer().transform(getClass().getClassLoader(), TARGET, null, null, subject());
        byte[] both = new GateHookTransformer(TARGET, "hit", "(Ljava/lang/Object;Ljava/lang/Object;)V",
                SINK, "attack", "Z", false, false).transform(getClass().getClassLoader(), TARGET,
                null, null, post);
        assertNotNull(both);
        Class<?> type = define(both);
        Object instance = type.getDeclaredConstructor().newInstance();

        type.getMethod("hit", Object.class, Object.class).invoke(instance, new Object(), new Object());

        assertEquals(java.util.Arrays.asList("gate", "capture", "body", "body", "post:false"),
                Sink.calls);

        Sink.calls.clear();
        Sink.allow = false;
        type.getMethod("hit", Object.class, Object.class).invoke(instance, new Object(), new Object());
        assertEquals(java.util.Collections.singletonList("gate"), Sink.calls);
    }

    @Test
    void retransformDoesNotDuplicateCaptureOrPost() {
        byte[] original = subject();
        AttackPostHookTransformer transformer = transformer();
        byte[] patched = transformer.transform(getClass().getClassLoader(), TARGET, null, null, original);
        assertNotNull(patched);
        assertNull(transformer.transform(getClass().getClassLoader(), TARGET, null, null, patched));
    }

    private static AttackPostHookTransformer transformer() {
        return new AttackPostHookTransformer(TARGET, "hit", "(Ljava/lang/Object;Ljava/lang/Object;)V", SINK);
    }

    private static byte[] subject() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "hit",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "body", "()V", false);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        Label different = new Label();
        method.visitJumpInsn(Opcodes.IF_ACMPNE, different);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(different);
        method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "body", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void defaultConstructor(ClassWriter writer) {
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    private static Class<?> define(byte[] bytes) {
        return new ClassLoader(AttackPostHookTransformerTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(TARGET.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }
}
