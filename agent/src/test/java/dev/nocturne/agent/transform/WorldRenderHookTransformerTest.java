package dev.nocturne.agent.transform;

import dev.nocturne.bootstrap.GameplaySink;
import dev.nocturne.client.bootstrap.GameplayHook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 真正加载并执行参数槽被裁掉/复用的世界渲染方法，不能只检查生成的指令。 */
class WorldRenderHookTransformerTest {
    private static final String TARGET = "test/WorldRenderSubject";
    private static final String HOOK = GameplayHook.class.getName().replace('.', '/');
    private static final String DESCRIPTOR = "(JFZ)V";

    @BeforeEach
    @AfterEach
    void reset() {
        GameplayHook.kill();
        GameplayHook.install(null);
        GameplaySink.resetForTests();
    }

    @Test
    void bothWorldCallbacksUseEntryPartialAfterLocalsAreChangedOrDiscarded() throws Exception {
        List<Float> rendered = new ArrayList<Float>();
        List<Float> captured = new ArrayList<Float>();
        GameplayHook.install(new GameplayHook.Sink() {
            @Override
            public void worldRender3D(float partialTicks) {
                rendered.add(partialTicks);
            }

            @Override
            public void worldRender3DCapture(float partialTicks) {
                captured.add(partialTicks);
            }
        });
        String[][] hooks = {{"worldRender3DBegin", "worldRender3D"},
                {"worldRender3DCaptureBegin", "worldRender3DCapture"}};
        for (String[] pair : hooks) {
            WorldRenderHookTransformer transformer = new WorldRenderHookTransformer(TARGET,
                    "render", DESCRIPTOR, HOOK, pair[0], pair[1]);
            byte[] patched = transformer.transform(getClass().getClassLoader(), TARGET,
                    null, null, subject());
            assertNotNull(patched);
            assertNull(transformer.transform(getClass().getClassLoader(), TARGET,
                    null, null, patched), "重放转换链不得再压入或消费一次");
            Class<?> type = new ClassLoader(getClass().getClassLoader()) {
                Class<?> define() {
                    return defineClass(TARGET.replace('/', '.'), patched, 0, patched.length);
                }
            }.define();
            type.getMethod("render", long.class, float.class, boolean.class)
                    .invoke(null, 0L, 0.25f, true);
            type.getMethod("render", long.class, float.class, boolean.class)
                    .invoke(null, 0L, 0.75f, false);
        }
        assertEquals(Arrays.asList(0.25f, 0.75f), rendered);
        assertEquals(Arrays.asList(0.25f, 0.75f), captured);
    }

    private static byte[] subject() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "render", DESCRIPTOR, null, null);
        method.visitCode();
        Label discarded = new Label();
        method.visitVarInsn(Opcodes.ILOAD, 3);
        method.visitJumpInsn(Opcodes.IFEQ, discarded);
        // 早退路径仍保留 float 类型，但不再保留入口值。
        method.visitInsn(Opcodes.FCONST_0);
        method.visitVarInsn(Opcodes.FSTORE, 2);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(discarded);
        method.visitFrame(Opcodes.F_FULL, 0, new Object[0], 0, new Object[0]);
        // 另一条路径裁掉所有形参，再把原 float 槽复用成 int。
        method.visitInsn(Opcodes.ICONST_1);
        method.visitVarInsn(Opcodes.ISTORE, 2);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
