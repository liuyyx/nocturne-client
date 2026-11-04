package dev.nocturne.ui.gl;

import dev.nocturne.ui.FakeBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OverlayDraw 到实际后端的原生与退化路径测试。 */
class BackendOverlayDrawTest {

    @Test
    void nativeLineDelegatesWithoutStepping() {
        FakeBackend backend = new FakeBackend(320, 240);
        backend.renderer.lineResult = true;
        BackendOverlayDraw draw = new BackendOverlayDraw(backend);

        draw.line(0, 0, 10, 10, 1.5f, 0xFFFFFFFF);

        assertEquals(java.util.Collections.singletonList("line"), backend.renderer.calls);
    }

    @Test
    void unsupportedLineFallsBackToRectangles() {
        FakeBackend backend = new FakeBackend(320, 240);
        BackendOverlayDraw draw = new BackendOverlayDraw(backend);

        draw.line(0, 0, 20, 0, 1f, 0xFFFFFFFF);

        assertEquals(1, backend.renderer.count("line"));
        assertTrue(backend.renderer.count("rect") > 1);
    }

    @Test
    void textureCallsDelegateDirectly() {
        FakeBackend backend = new FakeBackend(320, 240);
        BackendOverlayDraw draw = new BackendOverlayDraw(backend);

        int handle = draw.uploadTexture(0, 4, 4, new int[16]);
        draw.texturedQuad(handle, 0, 0, 4, 4, 0, 0, 1, 1, 0xFFFFFFFF);

        assertTrue(handle > 0);
        assertTrue(draw.supportsTextures());
        assertEquals(1, backend.renderer.uploadCalls);
        assertEquals(1, backend.renderer.quadCalls);
    }
}
