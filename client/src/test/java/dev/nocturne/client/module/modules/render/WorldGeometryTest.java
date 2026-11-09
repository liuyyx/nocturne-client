package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HoleESP 的垂直渐隐方向回归：屏幕顶部必须是 {@code topColor}，底部必须是 {@code bottomColor}。
 *
 * <p>Epsilon 的 {@code addFilledFadeBox(bounds, bottomColor, topColor)} 语义是底面填充色、顶面渐隐色。
 * 之前 HoleESP 用「整块填充色按距离混向 fade 色」实现，方向虽然偶合，但一旦把参数顺序写反，远距离
 * 洞的顶面就会变亮/底面变暗。这里用纯函数与一次投影后的条带颜色把方向钉死。
 */
class WorldGeometryTest {

    @Test
    void blendEndpointsAreExact() {
        int top = 0xFFFF0000;
        int bottom = 0xFF0000FF;
        assertEquals(top, WorldGeometry.blendArgb(top, bottom, 0f));
        assertEquals(bottom, WorldGeometry.blendArgb(top, bottom, 1f));
    }

    @Test
    void fadeFillStartsAtTopColorAndEndsAtBottomColor() {
        WorldProjection projection = new WorldProjection();
        // yaw=0 朝 +Z，框放在相机正前方：8 个角都可见，屏幕矩形有明确上下。
        projection.update(0d, 0d, 0d, 0f, 0f, 70f, 320, 240);

        RecordingDraw draw = new RecordingDraw();
        int top = 0xFFFF0000;
        int bottom = 0xFF0000FF;
        WorldGeometry.blockFilledFade(draw, projection,
                -1d, -1d, 5d, 1d, 1d, 7d, bottom, top);

        assertEquals(8, draw.rects.size(), "渐隐填充应为固定条带数");
        RecordingDraw.Rect first = draw.rects.get(0);
        RecordingDraw.Rect last = draw.rects.get(draw.rects.size() - 1);
        assertTrue(channel(first.color, 16) > 200 && channel(first.color, 0) < 60,
                "屏幕顶部条带必须是 topColor（红），实际=" + Integer.toHexString(first.color));
        assertTrue(channel(last.color, 0) > 200 && channel(last.color, 16) < 60,
                "屏幕底部条带必须是 bottomColor（蓝），实际=" + Integer.toHexString(last.color));
        assertTrue(first.y < last.y, "条带按屏幕 y 从顶到底排列");
    }

    private static int channel(int argb, int shift) {
        return argb >> shift & 255;
    }

    /** 只记录 rect 的绘制面。 */
    private static final class RecordingDraw implements OverlayDraw {
        private final List<Rect> rects = new ArrayList<Rect>();

        @Override
        public void rect(float x, float y, float width, float height, int argb) {
            rects.add(new Rect(x, y, width, height, argb));
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
            return 0f;
        }

        @Override
        public float textHeight() {
            return 9f;
        }

        @Override
        public void text(String text, float x, float y, float size, int argb) {
        }

        @Override
        public float textWidth(String text, float size) {
            return 0f;
        }

        @Override
        public float textHeight(float size) {
            return size;
        }

        @Override
        public int width() {
            return 320;
        }

        @Override
        public int height() {
            return 240;
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

        private static final class Rect {
            private final float x;
            private final float y;
            private final float width;
            private final float height;
            private final int color;

            private Rect(float x, float y, float width, float height, int color) {
                this.x = x;
                this.y = y;
                this.width = width;
                this.height = height;
                this.color = color;
            }
        }
    }
}
