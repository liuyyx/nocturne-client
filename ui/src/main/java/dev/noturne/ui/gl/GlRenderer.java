package dev.noturne.ui.gl;

import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;

/**
 * {@link Renderer} backed by fixed-function OpenGL (through {@link GlApi}).
 *
 * <p>Targets the 1.8.9-era pipeline (LWJGL2 / OpenGL 1.x): immediate mode, no shaders. Rounded
 * corners are built from three rectangles plus four triangle-fan quarter-circles, which is good
 * enough at UI sizes and needs no shader or stencil.
 */
public final class GlRenderer implements UiBackend {

    private static final int CORNER_STEPS = 6;

    private final GlApi gl;
    private final TextRenderer text;

    public GlRenderer(GlApi gl, TextRenderer text) {
        this.gl = gl;
        this.text = text == null ? TextRenderer.NONE : text;
    }

    public GlApi gl() {
        return gl;
    }

    @Override
    public String backendName() {
        return "gl-fixed";
    }

    @Override
    public void beginFrame() {
        // Fixed-function drawing needs no batch setup or shader binding.
    }

    @Override
    public void endFrame() {
        // Nothing to release.
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        if (width <= 0f || height <= 0f || color == null) {
            return;
        }
        gl.fillRect(x, y, width, height, color.rf(), color.gf(), color.bf(), color.af());
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        if (width <= 0f || height <= 0f || color == null) {
            return;
        }
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r <= 0.5f) {
            rect(x, y, width, height, color);
            return;
        }
        float cr = color.rf();
        float cg = color.gf();
        float cb = color.bf();
        float ca = color.af();

        // middle band, then the two side bands
        gl.fillRect(x + r, y, width - 2f * r, height, cr, cg, cb, ca);
        gl.fillRect(x, y + r, r, height - 2f * r, cr, cg, cb, ca);
        gl.fillRect(x + width - r, y + r, r, height - 2f * r, cr, cg, cb, ca);

        // four quarter circles
        quarter(x + r, y + r, r, 180f, 270f, cr, cg, cb, ca);
        quarter(x + width - r, y + r, r, 270f, 360f, cr, cg, cb, ca);
        quarter(x + width - r, y + height - r, r, 0f, 90f, cr, cg, cb, ca);
        quarter(x + r, y + height - r, r, 90f, 180f, cr, cg, cb, ca);
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        if (width <= 0f || height <= 0f || color == null) {
            return;
        }
        gl.strokeRect(x, y, width, height, lineWidth, color.rf(), color.gf(), color.bf(), color.af());
    }

    @Override
    public void text(String value, float x, float y, float size, Color color) {
        if (value == null || value.isEmpty()) {
            return;
        }
        text.draw(value, x, y, size, color);
    }

    @Override
    public float textWidth(String value, float size) {
        return text.width(value, size);
    }

    @Override
    public float textHeight(float size) {
        return text.height(size);
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        // Fixed-function 1.8.9 pipeline: no scissor binding here, so clipping is a no-op. Components
        // that need it keep children inside their own bounds.
    }

    @Override
    public void popClip() {
        // see pushClip
    }

    private void quarter(float cx, float cy, float radius, float startDeg, float endDeg,
                         float r, float g, float b, float a) {
        gl.color(r, g, b, a);
        gl.begin(GlApi.GL_TRIANGLE_FAN);
        gl.vertex(cx, cy);
        for (int i = 0; i <= CORNER_STEPS; i++) {
            double angle = Math.toRadians(startDeg + (endDeg - startDeg) * i / (double) CORNER_STEPS);
            gl.vertex(cx + (float) Math.cos(angle) * radius, cy + (float) Math.sin(angle) * radius);
        }
        gl.end();
    }
}
