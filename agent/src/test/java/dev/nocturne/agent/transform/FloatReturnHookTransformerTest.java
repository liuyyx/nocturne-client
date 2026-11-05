package dev.nocturne.agent.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Timer 浮点返回钩子的真实返回值测试。 */
class FloatReturnHookTransformerTest {
    private static final String TARGET = "dev/nocturne/agent/transform/FloatReturnSubject";
    private static final String SINK = Sink.class.getName().replace('.', '/');

    public static final class Sink {
        public static float scale(float value) {
            return value * 2.0f;
        }
    }

    @Test
    void scalesEveryReturnValue() throws Exception {
        byte[] patched = new FloatReturnHookTransformer(TARGET, "delta", "()F", SINK, "scale")
                .transform(getClass().getClassLoader(), TARGET, null, null, subject());
        Class<?> type = define(patched);
        Object instance = type.getDeclaredConstructor().newInstance();
        assertEquals(2.5f, (Float) type.getMethod("delta").invoke(instance), 0.0001f);
    }

    private static byte[] subject() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "delta", "()F", null, null);
        method.visitCode();
        method.visitLdcInsn(1.25f);
        method.visitInsn(Opcodes.FRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Class<?> define(byte[] bytes) {
        return new ClassLoader(FloatReturnHookTransformerTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(TARGET.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
    }
}
