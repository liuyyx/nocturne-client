package dev.nocturne.agent.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：类级去重会把「另一个方法已经织过同一个钩子」误当成「本方法已织入」。
 * EntityRenderDispatcher 的 shouldRender 先织后，同类的 render 门会被跳过。
 */
class TransformGuardTest {

    private static final String OWNER = "dev/nocturne/agent/transform/GuardSubject";
    private static final String HOOK = "dev/nocturne/agent/transform/TransformGuardTest$Hooks";
    private static final String HOOK_NAME = "entityRenderGate";

    static final class Hooks {
        static boolean entityRenderGate(Object entity) {
            return true;
        }
    }

    @Test
    void classLevelSeesAnyMethodButMethodLevelDoesNot() {
        byte[] bytes = subject();

        assertTrue(TransformGuard.containsHook(bytes, HOOK, HOOK_NAME),
                "precondition: one method calls the hook, so the class-level scan is true");
        assertTrue(TransformGuard.containsHookInMethod(bytes, OWNER, "withHook",
                "(Ljava/lang/Object;)V", HOOK, HOOK_NAME));
        assertFalse(TransformGuard.containsHookInMethod(bytes, OWNER, "withoutHook",
                        "(Ljava/lang/Object;)V", HOOK, HOOK_NAME),
                "the untouched method must not be skipped just because a sibling was woven");
    }

    private static byte[] subject() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        MethodVisitor hooked = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "withHook", "(Ljava/lang/Object;)V", null, null);
        hooked.visitCode();
        hooked.visitVarInsn(Opcodes.ALOAD, 0);
        hooked.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, HOOK_NAME,
                "(Ljava/lang/Object;)Z", false);
        hooked.visitInsn(Opcodes.POP);
        hooked.visitInsn(Opcodes.RETURN);
        hooked.visitMaxs(0, 0);
        hooked.visitEnd();

        MethodVisitor untouched = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "withoutHook", "(Ljava/lang/Object;)V", null, null);
        untouched.visitCode();
        untouched.visitInsn(Opcodes.RETURN);
        untouched.visitMaxs(0, 0);
        untouched.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
