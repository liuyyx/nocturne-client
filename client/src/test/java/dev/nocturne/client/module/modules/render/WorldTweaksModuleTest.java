package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldProjection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回归：雾色整帧遮罩绝不能在不可靠的后端上把世界涂黑。
 *
 * <p>真机（1.8.9 固定管线）曾出现「世界全黑、只剩 HUD 与准星」：整帧遮罩被同帧重复应用，
 * 25% 落成 ~44%，雾色接近黑时整屏黑幕。这里的断言就是那条防线的回归。
 */
class WorldTweaksModuleTest {

    @Test
    void legacyBackendSkipsFullScreenTint() {
        FakeDraw draw = new FakeDraw(false);
        new WorldTweaksModule().drawWorldOverlay(draw, null);
        assertEquals(0, draw.rects, "固定管线不得画整帧遮罩（否则世界可能全黑）");
    }

    @Test
    void modernBackendDrawsSemiTransparentTint() {
        FakeDraw draw = new FakeDraw(true);
        WorldTweaksModule module = new WorldTweaksModule();
        module.drawWorldOverlay(draw, null);
        assertEquals(1, draw.rects);
        assertEquals(0, draw.lastX);
        assertEquals(0, draw.lastY);
        assertEquals(draw.width, (int) draw.lastWidth);
        assertEquals(draw.height, (int) draw.lastHeight);
        int alpha = draw.lastColor >>> 24;
        assertEquals(true, alpha > 0 && alpha < 255, "遮罩必须是半透明，不能是不透明黑");
    }

    @Test
    void zeroAlphaRangeSkipsTint() {
        FakeDraw draw = new FakeDraw(true);
        WorldTweaksModule module = new WorldTweaksModule();
        // Fog Start == Fog End == 10（Fog End 的下限）→ alpha 0：不得下发任何图元。
        setNumber(module, "Fog Start", 10.0);
        setNumber(module, "Fog End", 10.0);
        module.drawWorldOverlay(draw, null);
        assertEquals(0, draw.rects);
    }

    private static void setNumber(WorldTweaksModule module, String name, double value) {
        for (dev.nocturne.client.value.Value<?> entry : module.values()) {
            if (name.equals(entry.name())
                    && entry instanceof dev.nocturne.client.value.NumberValue) {
                ((dev.nocturne.client.value.NumberValue) entry).set(value);
                return;
            }
        }
        throw new IllegalStateException("missing value " + name);
    }

    /** 只记录 rect 的最小绘制面；其余方法不使用。 */
    private static final class FakeDraw implements OverlayDraw {
        private final boolean reliable;
        private final int width = 320;
        private final int height = 240;
        private int rects;
        private float lastX;
        private float lastY;
        private float lastWidth;
        private float lastHeight;
        private int lastColor;

        private FakeDraw(boolean reliable) {
            this.reliable = reliable;
        }

        @Override
        public boolean reliableAlphaBlend() {
            return reliable;
        }

        @Override
        public void rect(float x, float y, float width, float height, int argb) {
            rects++;
            lastX = x;
            lastY = y;
            lastWidth = width;
            lastHeight = height;
            lastColor = argb;
        }

        @Override
        public void outline(float x, float y, float width, float height, float thickness, int argb) {
        }

        @Override
        public void text(String text, float x, float y, int argb) {
        }

        @Override
        public void roundedRect(float x, float y, float width, float height, float radius, int argb) {
        }

        @Override
        public float textWidth(String text) {
            return 0;
        }

        @Override
        public float textHeight() {
            return 9;
        }

        @Override
        public void text(String text, float x, float y, float size, int argb) {
        }

        @Override
        public float textWidth(String text, float size) {
            return 0;
        }

        @Override
        public float textHeight(float size) {
            return size;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public int uploadTexture(int id, int width, int height, int[] argb) {
            return 0;
        }

        @Override
        public void deleteTexture(int id) {
        }

        @Override
        public void texturedQuad(int texture, float x, float y, float width, float height,
                                 float u0, float v0, float u1, float v1, int argb) {
        }

        @Override
        public boolean supportsTextures() {
            return false;
        }
    }
}
