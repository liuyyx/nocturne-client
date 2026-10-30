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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** {@link PacketHookTransformer} 的替换与取消行为测试。 */
class PacketHookTransformerTest {

    private static final String TARGET = "dev/nocturne/agent/transform/PacketSubject";
    private static final String SINK = Sink.class.getName().replace('.', '/');

    public static final class Sink {
        static final List<Object> calls = new ArrayList<Object>();
        static Object replacement;

        public static Object filter(Object packet) {
            calls.add("filter:" + packet);
            return replacement;
        }

        public static void body(long channel, Object packet) {
            calls.add("body:" + channel + ":" + packet);
        }
    }

    @BeforeEach
    void reset() {
        Sink.calls.clear();
        Sink.replacement = null;
    }

    @Test
    void writesReplacementBackToTheOriginalArgumentSlot() throws Exception {
        Class<?> type = transformedSubject();
        Method send = type.getMethod("send", long.class, String.class);
        Object instance = type.getDeclaredConstructor().newInstance();
        Sink.replacement = "after";

        send.invoke(instance, 9L, "before");

        assertEquals(java.util.Arrays.asList("filter:before", "body:9:after"), Sink.calls);
    }

    @Test
    void nullReplacementCancelsTheOriginalCall() throws Exception {
        Class<?> type = transformedSubject();
        Method send = type.getMethod("send", long.class, String.class);
        Object instance = type.getDeclaredConstructor().newInstance();
        Sink.replacement = null;

        send.invoke(instance, 10L, "drop");

        assertEquals(java.util.Collections.singletonList("filter:drop"), Sink.calls);
    }

    private static Class<?> transformedSubject() {
        String descriptor = "(JLjava/lang/String;)V";
        byte[] patched = new PacketHookTransformer(TARGET, "send", descriptor, SINK, "filter", 1)
                .transform(PacketHookTransformerTest.class.getClassLoader(), TARGET, null, null,
                        packetSubject(descriptor));
        assertNotNull(patched);
        return define(patched);
    }

    private static byte[] packetSubject(String descriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "send", descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.LLOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "body",
                "(JLjava/lang/Object;)V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Class<?> define(byte[] bytecode) {
        return new ClassLoader(PacketHookTransformerTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(TARGET.replace('/', '.'), bytecode, 0, bytecode.length);
            }
        }.define();
    }
}
