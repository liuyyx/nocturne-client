package dev.nocturne.ui.gl;

import dev.nocturne.ui.FakeBackend;
import dev.nocturne.ui.render.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PlannedBackend 必须保持调用顺序：几何进入 plan，文本立即委托，不能本末倒置。 */
class PlannedBackendTest {

    @Test
    void textFlushesPendingGeometryBeforeItIsDrawn() {
        FakeBackend delegate = new FakeBackend(320, 240);
        PlannedBackend backend = new PlannedBackend(delegate);

        backend.beginFrame();
        backend.rect(1, 2, 30, 40, Color.of(0xFF112233));
        backend.text("A", 4, 5, 9, Color.of(0xFFFFFFFF));
        backend.endFrame();

        assertEquals(2, delegate.renderer.calls.size());
        assertEquals("rect", delegate.renderer.calls.get(0), "background must be submitted first");
        assertEquals("text:A", delegate.renderer.calls.get(1),
                "text must not be covered by the buffered rect");
    }

    @Test
    void lineFlushesPendingGeometryBeforeDelegating() {
        FakeBackend delegate = new FakeBackend(320, 240);
        PlannedBackend backend = new PlannedBackend(delegate);

        backend.beginFrame();
        backend.rect(1, 2, 30, 40, Color.of(0xFF112233));
        backend.line(0, 0, 10, 10, 1.5f, Color.WHITE);
        backend.endFrame();

        assertEquals(java.util.Arrays.asList("rect", "line"), delegate.renderer.calls);
    }
}
