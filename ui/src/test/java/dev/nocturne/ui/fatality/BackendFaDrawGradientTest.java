package dev.nocturne.ui.fatality;

import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用像素画布模拟只支持整数平涂的游戏绘制面。 */
class BackendFaDrawGradientTest {
    private static class Surface implements Renderer {
        final int[][] pixels = new int[16][16];
        final int[][] writes = new int[16][16];

        @Override public void rect(float x, float y, float w, float h, Color color) {
            int x1 = Math.round(x), y1 = Math.round(y);
            int x2 = Math.round(x + w), y2 = Math.round(y + h);
            for (int py = y1; py < y2; py++) {
                for (int px = x1; px < x2; px++) {
                    pixels[py][px] = color.argb;
                    writes[py][px]++;
                }
            }
        }
        @Override public void roundedRect(float x, float y, float w, float h, float r, Color color) { }
        @Override public void outline(float x, float y, float w, float h, float t, Color color) { }
        @Override public void text(String text, float x, float y, float size, Color color) { }
        @Override public float textWidth(String text, float size) { return 0; }
        @Override public float textHeight(float size) { return 0; }
        @Override public void pushClip(float x, float y, float w, float h) { }
        @Override public void popClip() { }
        @Override public int uploadTexture(int id, int w, int h, int[] argb) { return 0; }
        @Override public void deleteTexture(int id) { }
        @Override public void texturedQuad(int id, float x, float y, float w, float h,
                                           float u0, float v0, float u1, float v1, int argb) { }
    }

    private static final class TextureSurface extends Surface {
        int uploads;
        int draws;
        int[] uploaded;
        int tint;
        boolean tintable = true;
        @Override public boolean supportsTextures() { return true; }
        @Override public boolean supportsTextureTint() { return tintable; }
        @Override public int uploadTexture(int id, int w, int h, int[] argb) {
            uploads++;
            uploaded = argb.clone();
            return 1;
        }
        @Override public void texturedQuad(int id, float x, float y, float w, float h,
                                           float u0, float v0, float u1, float v1, int argb) {
            tint = argb;
            draws++;
        }
    }

    @Test
    void horizontalAndVerticalGradientsCoverEveryPixelOnce() {
        Surface surface = new Surface();
        BackendFaDraw draw = new BackendFaDraw();
        draw.begin(surface, 1f, 1f);
        draw.horizontal(1.2f, 2.2f, 8f, 6f, 0xFFFFFFFF, 0xFFFF0000);
        for (int y = 2; y < 8; y++) {
            for (int x = 1; x < 9; x++) {
                assertEquals(1, surface.writes[y][x]);
                assertEquals(surface.pixels[2][x], surface.pixels[y][x]);
            }
        }
        assertTrue((surface.pixels[2][1] & 255) > (surface.pixels[2][8] & 255));

        Surface vertical = new Surface();
        draw.begin(vertical, 1f, 1f);
        draw.vertical(1.2f, 2.2f, 8f, 6f, 0x00000000, 0xFF000000);
        for (int y = 2; y < 8; y++) {
            for (int x = 1; x < 9; x++) {
                assertEquals(1, vertical.writes[y][x]);
                assertEquals(vertical.pixels[y][1], vertical.pixels[y][x]);
            }
        }
        assertTrue((vertical.pixels[2][1] >>> 24) < (vertical.pixels[7][1] >>> 24));
    }

    @Test
    void fourCornerGradientPreservesBothAxesAndAlpha() {
        Surface surface = new Surface();
        BackendFaDraw draw = new BackendFaDraw();
        draw.begin(surface, 1f, 1f);
        draw.quad(1f, 1f, 4f, 4f, 0x00000000, 0x00FF0000, 0xFFFF00FF, 0xFF0000FF);
        for (int y = 1; y <= 4; y++) {
            for (int x = 1; x <= 4; x++) {
                assertEquals(1, surface.writes[y][x]);
            }
        }
        assertEquals(0x20200020, surface.pixels[1][1]);
        assertEquals(0x20DF0020, surface.pixels[1][4]);
        assertEquals(0xDF2000DF, surface.pixels[4][1]);
        assertEquals(0xDFDF00DF, surface.pixels[4][4]);
    }

    @Test
    void textureContainsBothAxesAndIsReusedAcrossFrames() {
        TextureSurface surface = new TextureSurface();
        BackendFaDraw draw = new BackendFaDraw();
        draw.begin(surface, 1f, 1f);
        draw.quad(1f, 1f, 4f, 4f, 0x00000000, 0x00FF0000, 0xFFFF00FF, 0xFF0000FF);
        assertEquals(0x20200020, surface.uploaded[0]);
        assertEquals(0x20DF0020, surface.uploaded[3]);
        assertEquals(0xDF2000DF, surface.uploaded[12]);
        assertEquals(0xDFDF00DF, surface.uploaded[15]);
        draw.begin(surface, 1f, 1f);
        draw.quad(1f, 1f, 4f, 4f, 0x00000000, 0x00FF0000, 0xFFFF00FF, 0xFF0000FF);
        assertEquals(1, surface.uploads);
        assertEquals(2, surface.draws);
        draw.quad(1f, 1f, 4f, 4f, 0x00000000, 0x00FF0000, 0x80FF00FF, 0x800000FF);
        assertEquals(1, surface.uploads);
        assertEquals(0x80FFFFFF, surface.tint);
    }

    @Test
    void bakedAlphaTexturePreservesColorAndReusesIdenticalPixels() {
        TextureSurface surface = new TextureSurface();
        surface.tintable = false;
        BackendFaDraw draw = new BackendFaDraw();
        draw.begin(surface, 1f, 1f);
        draw.quad(1f, 1f, 4f, 4f, 0x00FF0000, 0x80FF0000, 0x80FF0000, 0x00FF0000);
        assertEquals(0xFFFFFFFF, surface.tint);
        assertEquals(0x10FF0000, surface.uploaded[0]);
        assertEquals(0x70FF0000, surface.uploaded[3]);
        draw.quad(1f, 1f, 4f, 4f, 0x00FF0000, 0x80FF0000, 0x80FF0000, 0x00FF0000);
        assertEquals(1, surface.uploads);
    }
}
