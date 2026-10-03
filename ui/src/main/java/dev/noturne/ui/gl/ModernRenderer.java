package dev.noturne.ui.gl;

import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;

/**
 * {@link Renderer} for the OpenGL 3.2 core profile (Minecraft 1.13+ / 26.x).
 *
 * <p>The fixed-function {@link GlRenderer} cannot work here: core profile removed immediate mode.
 * Every shape is expanded into triangles on the CPU, uploaded to one reusable VBO and drawn with a
 * two-uniform shader (projection + flat colour). Colour is uniform rather than per-vertex because
 * the UI draws flat fills — batching gradients would cost more than it saves at this scale.
 *
 * <p>This is one of the two interchangeable backends; see {@code RenderBackends}.
 */
public final class ModernRenderer implements UiBackend {

    private static final String VERTEX_SHADER =
            "#version 150 core\n"
                    + "in vec2 aPos;\n"
                    + "uniform mat4 uProjection;\n"
                    + "void main() {\n"
                    + "    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#version 150 core\n"
                    + "uniform vec4 uColor;\n"
                    + "out vec4 fragColor;\n"
                    + "void main() {\n"
                    + "    fragColor = uColor;\n"
                    + "}\n";

    private static final int CORNER_SEGMENTS = 6;

    private final ModernGlApi gl;
    private final TextRenderer text;

    private int program;
    private int vertexArray;
    private int vertexBuffer;
    private int projectionLocation;
    private int colorLocation;
    private boolean ready;
    private float[] projection = new float[16];
    private int screenWidth;
    private int screenHeight;

    /** Scratch buffer reused for every draw call to keep the frame allocation-free. */
    private float[] scratch = new float[256];

    public ModernRenderer(ModernGlApi gl, TextRenderer text) {
        this.gl = gl;
        this.text = text == null ? TextRenderer.NONE : text;
    }

    public ModernGlApi gl() {
        return gl;
    }

    /** Compiles the shader and creates the buffer objects once, when a GL context is current. */
    public boolean initialise() {
        if (ready) {
            return true;
        }
        int vertex = gl.createShader(ModernGlApi.GL_VERTEX_SHADER);
        gl.shaderSource(vertex, VERTEX_SHADER);
        gl.compileShader(vertex);
        if (!gl.compileOk(vertex)) {
            System.err.println("[noturne] vertex shader failed: " + gl.shaderLog(vertex));
            return false;
        }
        int fragment = gl.createShader(ModernGlApi.GL_FRAGMENT_SHADER);
        gl.shaderSource(fragment, FRAGMENT_SHADER);
        gl.compileShader(fragment);
        if (!gl.compileOk(fragment)) {
            System.err.println("[noturne] fragment shader failed: " + gl.shaderLog(fragment));
            return false;
        }

        program = gl.createProgram();
        gl.attachShader(program, vertex);
        gl.attachShader(program, fragment);
        gl.linkProgram(program);
        projectionLocation = gl.uniformLocation(program, "uProjection");
        colorLocation = gl.uniformLocation(program, "uColor");

        vertexArray = gl.genVertexArray();
        vertexBuffer = gl.genBuffer();
        gl.bindVertexArray(vertexArray);
        gl.bindArrayBuffer(vertexBuffer);
        gl.enableVertexAttrib(0);
        gl.vertexAttribPointer(0, 2, 8, 0);

        ready = true;
        return true;
    }

    /** Sets the viewport used to build the projection matrix; call when the window resizes. */
    public void setViewport(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.projection = orthographic(width, height);
    }

    @Override
    public String backendName() {
        return "gl-core";
    }

    /** Applies per-frame GL state; call before drawing the GUI. */
    @Override
    public void beginFrame() {
        if (!ready && !initialise()) {
            return;
        }
        syncViewport();
        gl.useProgram(program);
        gl.bindVertexArray(vertexArray);
        gl.enableBlend();
        gl.disableTexture();
    }

    /** Tracks the window size so the projection stays correct after a resize or fullscreen toggle. */
    private void syncViewport() {
        int[] viewport = gl.getInteger(ModernGlApi.GL_VIEWPORT, 4);
        if (viewport == null) {
            return;
        }
        if (viewport[2] > 0 && viewport[3] > 0
                && (viewport[2] != screenWidth || viewport[3] != screenHeight)) {
            setViewport(viewport[2], viewport[3]);
        }
    }

    @Override
    public void endFrame() {
        gl.disableBlend();
    }

    // -------------------------------------------------------------- Renderer

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        if (width <= 0f || height <= 0f || color == null || !ready) {
            return;
        }
        ensureCapacity(12);
        int i = 0;
        // two triangles: (x,y) (x+w,y) (x+w,y+h) / (x,y) (x+w,y+h) (x,y+h)
        scratch[i++] = x;
        scratch[i++] = y;
        scratch[i++] = x + width;
        scratch[i++] = y;
        scratch[i++] = x + width;
        scratch[i++] = y + height;
        scratch[i++] = x;
        scratch[i++] = y;
        scratch[i++] = x + width;
        scratch[i++] = y + height;
        scratch[i++] = x;
        scratch[i++] = y + height;
        draw(scratch, i, color);
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        if (width <= 0f || height <= 0f || color == null || !ready) {
            return;
        }
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r <= 0.5f) {
            rect(x, y, width, height, color);
            return;
        }
        // centre cross
        rect(x + r, y, width - 2f * r, height, color);
        rect(x, y + r, r, height - 2f * r, color);
        rect(x + width - r, y + r, r, height - 2f * r, color);
        // corners, each a triangle fan collapsed to triangles, in screen coordinates
        corner(x + r, y + r, r, 180f, 270f, color);
        corner(x + width - r, y + r, r, 270f, 360f, color);
        corner(x + width - r, y + height - r, r, 0f, 90f, color);
        corner(x + r, y + height - r, r, 90f, 180f, color);
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        if (width <= 0f || height <= 0f || color == null || !ready) {
            return;
        }
        float t = Math.max(1f, lineWidth);
        rect(x, y, width, t, color);
        rect(x, y + height - t, width, t, color);
        rect(x, y + t, t, height - 2f * t, color);
        rect(x + width - t, y + t, t, height - 2f * t, color);
    }

    @Override
    public void text(String value, float x, float y, float size, Color color) {
        if (value == null || value.isEmpty()) {
            return;
        }
        // The game's font renderer issues its own GL calls, so the batch state is released first.
        endFrame();
        text.draw(value, x, y, size, color);
        beginFrame();
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
        // Scissor state needs the current FBO's height; left to the caller for now.
    }

    @Override
    public void popClip() {
        // see pushClip
    }

    // -------------------------------------------------------------- internals

    private void corner(float cx, float cy, float radius, float startDeg, float endDeg, Color color) {
        ensureCapacity((CORNER_SEGMENTS + 1) * 6);
        int i = 0;
        for (int segment = 0; segment < CORNER_SEGMENTS; segment++) {
            double a0 = Math.toRadians(startDeg + (endDeg - startDeg) * segment / (double) CORNER_SEGMENTS);
            double a1 = Math.toRadians(startDeg + (endDeg - startDeg) * (segment + 1) / (double) CORNER_SEGMENTS);
            float x0 = cx + (float) Math.cos(a0) * radius;
            float y0 = cy + (float) Math.sin(a0) * radius;
            float x1 = cx + (float) Math.cos(a1) * radius;
            float y1 = cy + (float) Math.sin(a1) * radius;
            // triangle: centre, p0, p1
            scratch[i++] = cx;
            scratch[i++] = cy;
            scratch[i++] = x0;
            scratch[i++] = y0;
            scratch[i++] = x1;
            scratch[i++] = y1;
        }
        draw(scratch, i, color);
    }

    private void ensureCapacity(int floats) {
        if (scratch.length < floats) {
            scratch = new float[Math.max(floats, scratch.length * 2)];
        }
    }

    private void draw(float[] data, int length, Color color) {
        int vertices = length / 2;
        if (vertices == 0) {
            return;
        }
        gl.bindArrayBuffer(vertexBuffer);
        float[] exact = new float[length];
        System.arraycopy(data, 0, exact, 0, length);
        gl.uploadArrayBuffer(exact);
        gl.uniformMatrix4fv(projectionLocation, projection);
        gl.uniform4f(colorLocation, color.rf(), color.gf(), color.bf(), color.af());
        gl.drawTriangles(0, vertices);
    }

    /** Column-major orthographic projection mapping (0,0) to the top-left corner. */
    static float[] orthographic(int width, int height) {
        float w = width <= 0 ? 1f : width;
        float h = height <= 0 ? 1f : height;
        return new float[]{
                2f / w, 0f, 0f, 0f,
                0f, -2f / h, 0f, 0f,
                0f, 0f, -1f, 0f,
                -1f, 1f, 0f, 1f,
        };
    }
}
