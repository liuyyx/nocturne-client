package dev.nocturne.client.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGeometryTest {

    @Test
    void boxDrawsTwelveViewportClampedEdges() {
        WorldProjection projection = projection();
        RecordingDraw draw = new RecordingDraw();

        WorldGeometry.box(draw, projection, -0.5, -0.5, 2.0, 0.5, 0.5, 3.0, 1.0f, 0xFFFFFFFF);

        assertEquals(12, draw.lines.size());
        for (float[] line : draw.lines) {
            for (float coordinate : line) {
                assertTrue(coordinate >= 0.0f && coordinate <= 100.0f);
            }
        }
    }

    @Test
    void circleDrawsOneEdgePerSegment() {
        WorldProjection projection = projection();
        RecordingDraw draw = new RecordingDraw();

        WorldGeometry.circle(draw, projection, 0.0, 0.0, 3.0, 1.0, 8, 1.0f, 0xFFFFFFFF);

        assertEquals(8, draw.lines.size());
    }

    @Test
    void silhouetteEnclosesTheVisibleProjectedCornersWithTheRequestedColor() {
        RecordingDraw draw = new RecordingDraw();
        WorldGeometry.silhouette(draw, projection(), -0.5, -0.5, 2, 0.5, 0.5, 3, 0x40123456);

        assertEquals(1, draw.rectangles.size());
        float halfExtent = (float) (12.5 / Math.tan(Math.toRadians(35)));
        float[] rectangle = draw.rectangles.get(0);
        assertEquals(50f - halfExtent, rectangle[0], 0.001f);
        assertEquals(50f - halfExtent, rectangle[1], 0.001f);
        assertEquals(2f * halfExtent, rectangle[2], 0.001f);
        assertEquals(2f * halfExtent, rectangle[3], 0.001f);
        assertEquals(0x40123456, draw.rectangleColors.get(0).intValue());
        assertTrue(draw.lines.isEmpty());
    }

    @Test
    void silhouetteSkipsHiddenCornersAndDoesNotReusePreviousVisibleBounds() {
        RecordingDraw draw = new RecordingDraw();
        WorldProjection projection = projection();
        WorldGeometry.silhouette(draw, projection, -0.5, -0.5, -2, 0.5, 0.5, 3, 0xFFFFFFFF);
        assertEquals(1, draw.rectangles.size());
        float halfExtent = (float) (25.0 / (3.0 * Math.tan(Math.toRadians(35))));
        assertEquals(2f * halfExtent, draw.rectangles.get(0)[2], 0.001f);
        assertEquals(2f * halfExtent, draw.rectangles.get(0)[3], 0.001f);

        WorldGeometry.silhouette(draw, projection, -0.5, -0.5, -3, 0.5, 0.5, -2, 0xFFFFFFFF);
        WorldGeometry.silhouette(draw, new WorldProjection(), -0.5, -0.5, 2, 0.5, 0.5, 3,
                0xFFFFFFFF);
        assertEquals(1, draw.rectangles.size(), "全部不可见或投影未就绪时不得残留上一盒填充");
    }

    @Test
    void verticalBoxDrawsTwoClosedRingsAndFourFullHeightGenerators() {
        WorldProjection projection = projection();
        RecordingDraw draw = new RecordingDraw();
        WorldGeometry.verticalBox(draw, projection, 0, -0.5, 4, 0.5, 1, 8, 2.5f, 0x80123456);

        assertEquals(20, draw.lines.size());
        for (int i = 0; i < draw.lines.size(); i++) {
            assertEquals(2.5f, draw.lineWidths.get(i), 0.001f);
            assertEquals(0x80123456, draw.lineColors.get(i).intValue());
        }
        // 每圈八边首尾闭合，上圈和下圈不能被误画到同一高度。
        for (int start : new int[]{0, 8}) {
            assertEquals(draw.lines.get(start)[0], draw.lines.get(start + 7)[2], 0.001f);
            assertEquals(draw.lines.get(start)[1], draw.lines.get(start + 7)[3], 0.001f);
        }
        assertTrue(draw.lines.get(0)[1] > draw.lines.get(8)[1]);
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI * 0.5 * i;
            double x = Math.cos(angle) * 0.5;
            double z = 4 + Math.sin(angle) * 0.5;
            WorldProjection.ScreenPoint bottom = new WorldProjection.ScreenPoint();
            WorldProjection.ScreenPoint top = new WorldProjection.ScreenPoint();
            projection.project(x, -0.5, z, bottom);
            projection.project(x, 0.5, z, top);
            float[] line = draw.lines.get(16 + i);
            assertEquals(bottom.x, line[0], 0.001f);
            assertEquals(bottom.y, line[1], 0.001f);
            assertEquals(top.x, line[2], 0.001f);
            assertEquals(top.y, line[3], 0.001f);
        }
    }

    @Test
    void verticalBoxBehindCameraAndNonPositiveSegmentsDrawNothing() {
        RecordingDraw draw = new RecordingDraw();
        WorldGeometry.verticalBox(draw, projection(), 0, 0, -4, 0.5, 1, 8, 1f, 0xFFFFFFFF);
        WorldGeometry.verticalBox(draw, projection(), 0, 0, 4, 0.5, 1, 0, 1f, 0xFFFFFFFF);
        WorldGeometry.verticalBox(draw, projection(), 0, 0, 4, 0.5, 1, -1, 1f, 0xFFFFFFFF);
        assertTrue(draw.lines.isEmpty());
    }

    @Test
    void labelUsesProjectedCoordinatesSizeAndColorAndSkipsInvisiblePoints() {
        RecordingDraw draw = new RecordingDraw();
        WorldGeometry.label(draw, projection(), "visible", 0, 0, 3, 11f, 0xABCDEF12);
        WorldGeometry.label(draw, projection(), "behind", 0, 0, -3, 12f, 0xFFFFFFFF);
        WorldGeometry.label(draw, new WorldProjection(), "unready", 0, 0, 3, 12f, 0xFFFFFFFF);

        assertEquals(1, draw.texts.size());
        assertEquals("visible", draw.texts.get(0));
        float[] position = draw.textPositions.get(0);
        assertEquals(50f, position[0], 0.001f);
        assertEquals(50f, position[1], 0.001f);
        assertEquals(11f, position[2], 0.001f);
        assertEquals(0xABCDEF12, draw.textColors.get(0).intValue());
    }

    private static WorldProjection projection() {
        WorldProjection projection = new WorldProjection();
        projection.update(0.0, 0.0, 0.0, 0.0f, 0.0f, 70.0f, 100, 100);
        return projection;
    }

    private static final class RecordingDraw implements OverlayDraw {
        private final List<float[]> lines = new ArrayList<float[]>();
        private final List<Float> lineWidths = new ArrayList<Float>();
        private final List<Integer> lineColors = new ArrayList<Integer>();
        private final List<float[]> rectangles = new ArrayList<float[]>();
        private final List<Integer> rectangleColors = new ArrayList<Integer>();
        private final List<String> texts = new ArrayList<String>();
        private final List<float[]> textPositions = new ArrayList<float[]>();
        private final List<Integer> textColors = new ArrayList<Integer>();

        @Override
        public void line(float x1, float y1, float x2, float y2, float thickness, int argb) {
            lines.add(new float[]{x1, y1, x2, y2});
            lineWidths.add(thickness);
            lineColors.add(argb);
        }

        @Override
        public void rect(float x, float y, float width, float height, int argb) {
            rectangles.add(new float[]{x, y, width, height});
            rectangleColors.add(argb);
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
            return 0.0f;
        }

        @Override
        public float textHeight() {
            return 0.0f;
        }

        @Override
        public void text(String text, float x, float y, float size, int argb) {
            texts.add(text);
            textPositions.add(new float[]{x, y, size});
            textColors.add(argb);
        }

        @Override
        public float textWidth(String text, float size) {
            return 0.0f;
        }

        @Override
        public float textHeight(float size) {
            return 0.0f;
        }

        @Override
        public int width() {
            return 100;
        }

        @Override
        public int height() {
            return 100;
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
