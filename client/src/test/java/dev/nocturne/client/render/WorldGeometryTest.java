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

    private static WorldProjection projection() {
        WorldProjection projection = new WorldProjection();
        projection.update(0.0, 0.0, 0.0, 0.0f, 0.0f, 70.0f, 100, 100);
        return projection;
    }

    private static final class RecordingDraw implements OverlayDraw {
        private final List<float[]> lines = new ArrayList<float[]>();

        @Override
        public void line(float x1, float y1, float x2, float y2, float thickness, int argb) {
            lines.add(new float[]{x1, y1, x2, y2});
        }

        @Override
        public void rect(float x, float y, float width, float height, int argb) {
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
