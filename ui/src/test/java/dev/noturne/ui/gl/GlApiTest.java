package dev.noturne.ui.gl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlApiTest {

    @BeforeEach
    void reset() {
        FakeGl.reset();
    }

    @Test
    void bindsToAUsableGlClass() {
        GlApi api = GlApi.bind(FakeGl.class);
        assertNotNull(api);
    }

    @Test
    void rejectsAClassWithoutGlMethods() {
        assertNull(GlApi.bind(Object.class));
        assertNull(GlApi.bind((Class<?>) null));
        assertNull(GlApi.bind("does.not.Exist", getClass().getClassLoader()));
    }

    @Test
    void fillRectEmitsOneColorAndFourVertices() {
        GlApi api = GlApi.bind(FakeGl.class);
        api.fillRect(10f, 20f, 30f, 40f, 1f, 0f, 0f, 1f);

        assertEquals(1, FakeGl.colorCalls);
        assertEquals(1, FakeGl.beginCalls);
        assertEquals(4, FakeGl.vertexCalls);
        assertEquals(1, FakeGl.endCalls);
        assertEquals(GlApi.GL_QUADS, FakeGl.lastBeginMode);
    }

    @Test
    void strokeRectSetsLineWidthAndLoops() {
        GlApi api = GlApi.bind(FakeGl.class);
        api.strokeRect(0f, 0f, 10f, 10f, 2f, 1f, 1f, 1f, 1f);

        assertEquals(1, FakeGl.lineWidthCalls);
        assertEquals(2f, FakeGl.lastLineWidth, 1e-6);
        assertEquals(4, FakeGl.vertexCalls);
        assertEquals(GlApi.GL_LINE_LOOP, FakeGl.lastBeginMode);
    }
}
