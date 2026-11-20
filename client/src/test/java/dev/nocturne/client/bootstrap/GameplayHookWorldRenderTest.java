package dev.nocturne.client.bootstrap;

import dev.nocturne.bootstrap.GameplaySink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 世界渲染的无参尾钩必须消费自己的入口捕获值，且在回调失败后保持配对。 */
class GameplayHookWorldRenderTest {
    @BeforeEach
    @AfterEach
    void reset() {
        GameplayHook.kill();
        GameplayHook.install(null);
        GameplaySink.resetForTests();
        GameplayHook.resetFailureLogsForTests();
    }

    @Test
    void nestedRenderAndCameraCallbacksKeepIndependentPartials() {
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
        GameplayHook.worldRender3DBegin(0.25f);
        GameplayHook.worldRender3DCaptureBegin(0.5f);
        GameplayHook.worldRender3DBegin(0.75f);
        GameplayHook.worldRender3D();
        GameplayHook.worldRender3DCapture();
        GameplayHook.worldRender3D();
        GameplayHook.worldRender3D();
        assertEquals(Arrays.asList(0.75f, 0.25f), rendered);
        assertEquals(Arrays.asList(0.5f), captured);
    }

    @Test
    void callbackFailureAndPanicCannotReplayCapturedPartials() {
        List<Float> received = new ArrayList<Float>();
        GameplayHook.install(new GameplayHook.Sink() {
            @Override
            public void worldRender3D(float partialTicks) {
                if (partialTicks == 0.75f) {
                    throw new IllegalStateException("render callback failure");
                }
                received.add(partialTicks);
            }
        });
        GameplayHook.worldRender3DBegin(0.25f);
        GameplayHook.worldRender3DBegin(0.75f);
        GameplayHook.worldRender3D();
        GameplayHook.worldRender3D();
        GameplayHook.worldRender3D();
        GameplayHook.worldRender3DBegin(0.5f);
        GameplayHook.kill();
        GameplayHook.worldRender3D();
        GameplayHook.worldRender3DBegin(1f);
        GameplayHook.worldRender3D();
        assertEquals(Arrays.asList(0.25f), received);
    }
}
