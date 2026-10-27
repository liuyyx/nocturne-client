package dev.nocturne.ui.fatality;

import dev.nocturne.ui.gl.PlayerModelPreview;
import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模型区域和鼠标必须一起变换；无模型能力的后端不得谎称已绘制。 */
class BackendFaDrawPreviewTest {
    private static class Surface implements Renderer {
        @Override public void rect(float x, float y, float w, float h, Color color) { }
        @Override public void roundedRect(float x, float y, float w, float h, float r, Color color) { }
        @Override public void outline(float x, float y, float w, float h, float t, Color color) { }
        @Override public void text(String text, float x, float y, float size, Color color) { }
        @Override public float textWidth(String text, float size) { return 0; }
        @Override public float textHeight(float size) { return 0; }
        @Override public void pushClip(float x, float y, float w, float h) { }
        @Override public void popClip() { }
        @Override public int uploadTexture(int id, int w, int h, int[] argb) { return id; }
        @Override public void deleteTexture(int id) { }
        @Override public void texturedQuad(int id, float x, float y, float w, float h,
                                           float u0, float v0, float u1, float v1, int argb) { }
    }

    private static final class PreviewSurface extends Surface implements PlayerModelPreview {
        int calls;
        final float[] coords = new float[6];
        @Override
        public boolean drawPlayer(Object player, float left, float top, float right, float bottom,
                                  float mouseX, float mouseY) {
            calls++;
            coords[0] = left;
            coords[1] = top;
            coords[2] = right;
            coords[3] = bottom;
            coords[4] = mouseX;
            coords[5] = mouseY;
            return true;
        }
    }

    @Test
    void modelAndPointerUseSameTransformAndNeverSubmitAbsentPlayer() {
        PreviewSurface surface = new PreviewSurface();
        BackendFaDraw draw = new BackendFaDraw();
        draw.begin(surface, 2f, 1f);
        draw.translate(3f, 5f);
        assertTrue(draw.player(new Object(), 10f, 20f, 40f, 80f, 25f, 30f));
        assertEquals(1, surface.calls);
        assertEquals(26f, surface.coords[0]);
        assertEquals(50f, surface.coords[1]);
        assertEquals(106f, surface.coords[2]);
        assertEquals(210f, surface.coords[3]);
        assertEquals(56f, surface.coords[4]);
        assertEquals(70f, surface.coords[5]);
        assertFalse(draw.player(null, 10f, 20f, 40f, 80f, 25f, 30f));
        assertEquals(1, surface.calls);
    }

    @Test
    void unsupportedRendererCannotClaimModelWasDrawn() {
        BackendFaDraw draw = new BackendFaDraw();
        draw.begin(new Surface(), 1f, 1f);
        assertFalse(draw.player(new Object(), 10f, 20f, 40f, 80f, 25f, 30f));
    }
}
