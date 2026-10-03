package dev.noturne.ui.gl;

import dev.noturne.ui.render.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlRendererTest {

    /** Captures the text calls so the seam can be asserted without a real font. */
    static final class RecordingText implements TextRenderer {
        final List<String> drawn = new ArrayList<String>();

        @Override
        public void draw(String text, float x, float y, float size, Color color) {
            drawn.add(text);
        }

        @Override
        public float width(String text, float size) {
            return text.length() * size;
        }

        @Override
        public float height(float size) {
            return size;
        }
    }

    private GlRenderer renderer;
    private RecordingText text;

    @BeforeEach
    void setUp() {
        FakeGl.reset();
        text = new RecordingText();
        renderer = new GlRenderer(GlApi.bind(FakeGl.class), text);
    }

    @Test
    void rectDrawsOneQuad() {
        renderer.rect(0f, 0f, 10f, 10f, Color.WHITE);
        assertEquals(1, FakeGl.beginCalls);
        assertEquals(4, FakeGl.vertexCalls);
        assertEquals(1, FakeGl.endCalls);
    }

    @Test
    void degenerateRectsAreSkipped() {
        renderer.rect(0f, 0f, 0f, 10f, Color.WHITE);
        renderer.rect(0f, 0f, 10f, 0f, Color.WHITE);
        renderer.rect(0f, 0f, 10f, 10f, null);
        assertEquals(0, FakeGl.beginCalls);
    }

    @Test
    void roundedRectFallsBackToAPlainRectWhenRadiusIsTiny() {
        renderer.roundedRect(0f, 0f, 20f, 20f, 0f, Color.WHITE);
        assertEquals(1, FakeGl.beginCalls, "a square corner is just a quad");
        assertEquals(4, FakeGl.vertexCalls);
    }

    @Test
    void roundedRectBuildsBandsAndFans() {
        renderer.roundedRect(0f, 0f, 40f, 20f, 4f, Color.WHITE);
        // 3 bands (quads) + 4 corners (fans)
        assertEquals(7, FakeGl.beginCalls);
        assertEquals(7, FakeGl.endCalls, "every band and corner closes its own primitive");
        assertTrue(FakeGl.vertexCalls > 12, "corners add fan vertices");
    }

    @Test
    void outlineUsesLineLoop() {
        renderer.outline(0f, 0f, 10f, 10f, 1f, Color.WHITE);
        assertEquals(GlApi.GL_LINE_LOOP, FakeGl.lastBeginMode);
        assertEquals(4, FakeGl.vertexCalls);
    }

    @Test
    void textGoesThroughTheSeam() {
        renderer.text("hello", 1f, 2f, 14f, Color.WHITE);
        assertEquals(1, text.drawn.size());
        assertEquals("hello", text.drawn.get(0));
        assertEquals(70f, renderer.textWidth("hello", 14f), 1e-3);

        renderer.text("", 0f, 0f, 14f, Color.WHITE);
        assertEquals(1, text.drawn.size(), "empty text is skipped");
    }

    @Test
    void nullTextRendererIsTolerated() {
        GlRenderer bare = new GlRenderer(GlApi.bind(FakeGl.class), null);
        bare.text("ignored", 0f, 0f, 12f, Color.WHITE);
        assertEquals(24f, bare.textWidth("abcd", 12f), 1e-3);
    }
}
