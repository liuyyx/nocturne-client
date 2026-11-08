package dev.nocturne.agent.transform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 实体门控的两种返回形态：void（1.13+）与 boolean（1.8.9 doRenderEntity）。
 * 旧实现只接受 void，构造期抛异常被反射包成 InvocationTargetException，
 * 1.8.9 的实体门控（NoRender/YSM）整条静默禁用。
 */
class EntityRenderGateTransformerTest {

    private static final String BOOLEAN_TARGET = "dev/nocturne/agent/transform/GateBoolSubject";
    private static final String VOID_TARGET = "dev/nocturne/agent/transform/GateVoidSubject";
    private static final String SINK = Sink.class.getName().replace('.', '/');

    public static final class Sink {
        static final List<String> calls = new ArrayList<String>();
        static boolean allow = true;

        public static boolean entityRenderGate(Object entity) {
            calls.add("gate:" + entity);
            return allow;
        }

        public static void body(Object entity) {
            calls.add("body:" + entity);
        }
    }

    @BeforeEach
    void reset() {
        Sink.calls.clear();
        Sink.allow = true;
    }

    @Test
    void booleanEntryIsCancelledWithoutVerifyError() throws Exception {
        byte[] patched = new EntityRenderGateTransformer(BOOLEAN_TARGET, "hit",
                "(Ljava/lang/Object;)Z", SINK)
                .transform(getClass().getClassLoader(), BOOLEAN_TARGET, null, null,
                        subject(BOOLEAN_TARGET, "(Ljava/lang/Object;)Z", true));
        assertNotNull(patched, "transformer must accept a boolean-returning entry");
        Class<?> type = define(BOOLEAN_TARGET, patched);
        Object instance = type.getDeclaredConstructor().newInstance();
        Method hit = type.getMethod("hit", Object.class);

        Sink.allow = false;
        assertEquals(Boolean.FALSE, hit.invoke(instance, "entity"));
        assertEquals(1, Sink.calls.size(), "cancelled call must not run the body: " + Sink.calls);

        Sink.calls.clear();
        Sink.allow = true;
        assertEquals(Boolean.TRUE, hit.invoke(instance, "entity"));
        assertEquals(java.util.Arrays.asList("gate:entity", "body:entity"), Sink.calls);
    }

    @Test
    void voidEntryKeepsItsReturnPath() throws Exception {
        byte[] patched = new EntityRenderGateTransformer(VOID_TARGET, "hit",
                "(Ljava/lang/Object;)V", SINK)
                .transform(getClass().getClassLoader(), VOID_TARGET, null, null,
                        subject(VOID_TARGET, "(Ljava/lang/Object;)V", false));
        assertNotNull(patched);
        Class<?> type = define(VOID_TARGET, patched);
        Object instance = type.getDeclaredConstructor().newInstance();
        Method hit = type.getMethod("hit", Object.class);

        Sink.allow = false;
        hit.invoke(instance, "entity");
        assertEquals(1, Sink.calls.size(), "cancelled call must not run the body: " + Sink.calls);

        Sink.calls.clear();
        Sink.allow = true;
        hit.invoke(instance, "entity");
        assertEquals(java.util.Arrays.asList("gate:entity", "body:entity"), Sink.calls);
    }

    @Test
    void unsupportedReturnShapeIsRejectedUpFront() {
        try {
            new EntityRenderGateTransformer(VOID_TARGET, "hit", "(Ljava/lang/Object;)F", SINK);
            throw new AssertionError("float-returning entry must be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private static byte[] subject(String name, String descriptor, boolean booleanResult) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "hit", descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "body", "(Ljava/lang/Object;)V", false);
        if (booleanResult) {
            method.visitInsn(Opcodes.ICONST_1);
            method.visitInsn(Opcodes.IRETURN);
        } else {
            method.visitInsn(Opcodes.RETURN);
        }
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Class<?> define(String internalName, byte[] bytecode) {
        return new ClassLoader(EntityRenderGateTransformerTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(internalName.replace('/', '.'), bytecode, 0, bytecode.length);
            }
        }.define();
    }
}
