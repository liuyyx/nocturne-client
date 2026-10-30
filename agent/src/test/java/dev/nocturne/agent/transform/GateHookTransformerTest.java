package dev.nocturne.agent.transform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link GateHookTransformer} 的真实字节码行为测试。 */
class GateHookTransformerTest {

    private static final String TARGET = "dev/nocturne/agent/transform/GateSubject";
    private static final String SINK = Sink.class.getName().replace('.', '/');

    /** 测试钩子与方法体的共享记录。 */
    public static final class Sink {
        static final List<String> calls = new ArrayList<String>();
        static boolean allow;

        public static boolean gate(long window, Object value, int action) {
            calls.add("gate:" + window + ":" + value + ":" + action);
            return allow;
        }

        public static boolean gateBoolean() {
            calls.add("gateBoolean");
            return allow;
        }

        public static void body(long window, Object value, int action) {
            calls.add("body:" + window + ":" + value + ":" + action);
        }

        public static void after(Object instance) {
            calls.add("after:" + instance.getClass().getSimpleName());
        }

        public static void before(Object instance) {
            calls.add("before:" + instance.getClass().getSimpleName());
        }

        public static void earlyBody() {
            calls.add("earlyBody");
        }
    }

    @BeforeEach
    void reset() {
        Sink.calls.clear();
        Sink.allow = true;
    }

    @Test
    void forwardsArgumentsAndHonoursGateDecision() throws Exception {
        String descriptor = "(JLjava/lang/String;I)V";
        byte[] patched = new GateHookTransformer(TARGET, "hit", descriptor, SINK, "gate",
                "Z", false, false).transform(getClass().getClassLoader(), TARGET, null, null,
                gateSubject(descriptor));
        assertNotNull(patched);
        Class<?> type = define(patched);
        Method hit = type.getMethod("hit", long.class, String.class, int.class);
        Object instance = type.getDeclaredConstructor().newInstance();

        hit.invoke(instance, 7L, "packet", 1);
        assertEquals(java.util.Arrays.asList("gate:7:packet:1", "body:7:packet:1"), Sink.calls);

        Sink.calls.clear();
        Sink.allow = false;
        hit.invoke(instance, 8L, "blocked", 0);
        assertEquals(java.util.Collections.singletonList("gate:8:blocked:0"), Sink.calls);
    }

    @Test
    void methodEndHookRunsOnEveryReturnPath() throws Exception {
        byte[] patched = new GateHookTransformer(TARGET, "hit", "(Z)V", SINK, "after",
                "V", true, true).transform(getClass().getClassLoader(), TARGET, null, null,
                earlyReturnSubject());
        assertNotNull(patched);
        Class<?> type = define(patched);
        Method hit = type.getMethod("hit", boolean.class);
        Object instance = type.getDeclaredConstructor().newInstance();

        hit.invoke(instance, true);
        assertEquals(java.util.Arrays.asList("after:GateSubject"), Sink.calls);

        Sink.calls.clear();
        hit.invoke(instance, false);
        assertEquals(java.util.Arrays.asList("earlyBody", "after:GateSubject"), Sink.calls);
    }

    @Test
    void chainedTransformersKeepBothHeadAndTailHooks() throws Exception {
        byte[] original = earlyReturnSubject();
        byte[] head = new GateHookTransformer(TARGET, "hit", "(Z)V", SINK, "before",
                "V", false, true).transform(getClass().getClassLoader(), TARGET, null, null, original);
        byte[] both = new GateHookTransformer(TARGET, "hit", "(Z)V", SINK, "after",
                "V", true, true).transform(getClass().getClassLoader(), TARGET, null, null, head);
        Class<?> type = define(both);
        Object instance = type.getDeclaredConstructor().newInstance();

        type.getMethod("hit", boolean.class).invoke(instance, false);

        assertEquals(java.util.Arrays.asList("before:GateSubject", "earlyBody", "after:GateSubject"),
                Sink.calls);
    }

    @Test
    void gateInstalledAfterTailSkipsTailOnCancellation() throws Exception {
        String descriptor = "(JLjava/lang/String;I)V";
        byte[] original = gateSubject(descriptor);
        byte[] tail = new GateHookTransformer(TARGET, "hit", descriptor, SINK, "after",
                "V", true, true).transform(getClass().getClassLoader(), TARGET, null, null, original);
        byte[] both = new GateHookTransformer(TARGET, "hit", descriptor, SINK, "gate",
                "Z", false, false).transform(getClass().getClassLoader(), TARGET, null, null, tail);
        Class<?> type = define(both);
        Object instance = type.getDeclaredConstructor().newInstance();
        Sink.allow = false;

        type.getMethod("hit", long.class, String.class, int.class).invoke(instance, 1L, "x", 0);

        assertEquals(java.util.Collections.singletonList("gate:1:x:0"), Sink.calls);
    }

    @Test
    void nonVoidTargetReturnsDefaultWhenCancelled() throws Exception {
        byte[] patched = new GateHookTransformer(TARGET, "hit", "()Z", SINK, "gateBoolean",
                "Z", false, false).transform(getClass().getClassLoader(), TARGET, null, null,
                booleanSubject());
        assertNotNull(patched);
        Class<?> type = define(patched);
        Method hit = type.getMethod("hit");
        Object instance = type.getDeclaredConstructor().newInstance();

        assertEquals(Boolean.TRUE, hit.invoke(instance));
        Sink.allow = false;
        assertEquals(Boolean.FALSE, hit.invoke(instance));
        assertEquals(java.util.Arrays.asList("gateBoolean", "earlyBody", "gateBoolean"), Sink.calls);
    }

    @Test
    void leavesOtherMethodsAndInvalidShapesUntouched() {
        byte[] original = gateSubject("()V");
        assertNull(new GateHookTransformer(TARGET, "other", "()V", SINK, "gate",
                "Z", false, false).transform(getClass().getClassLoader(), TARGET, null, null, original));
        assertThrows(IllegalArgumentException.class, () -> new GateHookTransformer(
                TARGET, "hit", "()V", SINK, "gate", "Z", true, false));
    }

    private static byte[] booleanSubject() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "hit", "()Z", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "earlyBody", "()V", false);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] gateSubject(String descriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "hit", descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.LLOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "body",
                "(JLjava/lang/Object;I)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] earlyReturnSubject() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        defaultConstructor(writer);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "hit", "(Z)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ILOAD, 1);
        Label body = new Label();
        method.visitJumpInsn(Opcodes.IFEQ, body);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(body);
        method.visitFrame(Opcodes.F_NEW, 2, new Object[]{TARGET, Opcodes.INTEGER}, 0, null);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "earlyBody", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void defaultConstructor(ClassWriter writer) {
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    private static Class<?> define(byte[] bytecode) {
        return new ClassLoader(GateHookTransformerTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(TARGET.replace('/', '.'), bytecode, 0, bytecode.length);
            }
        }.define();
    }
}
