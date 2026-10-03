package dev.noturne.ui.gl;

import dev.noturne.client.game.Reflect;

import java.lang.reflect.Method;

/**
 * Reflective binding to LWJGL's fixed-function {@code GL11}.
 *
 * <p>We cannot link against LWJGL at compile time (the injector runs on the operator's JVM, not in
 * the game), so every call goes through a method handle resolved against the game's class loader.
 *
 * <p>Only the immediate-mode subset the UI needs is bound. GL enums are inlined as constants —
 * they are part of the OpenGL ABI and never change.
 */
public final class GlApi {

    public static final int GL_LINES = 1;
    public static final int GL_LINE_LOOP = 2;
    public static final int GL_TRIANGLE_FAN = 6;
    public static final int GL_QUADS = 7;
    public static final int GL_BLEND = 3042;
    public static final int GL_SRC_ALPHA = 770;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 771;
    public static final int GL_LINE_SMOOTH = 2848;
    public static final int GL_TEXTURE_2D = 3553;
    public static final int GL_CULL_FACE = 2884;

    private final Method color4f;
    private final Method begin;
    private final Method end;
    private final Method vertex2f;
    private final Method enable;
    private final Method disable;
    private final Method blendFunc;
    private final Method pushMatrix;
    private final Method popMatrix;
    private final Method translatef;
    private final Method scalef;
    private final Method lineWidth;
    private final Method texCoord2f;
    private final Method bindTexture;

    private GlApi(Method color4f, Method begin, Method end, Method vertex2f, Method enable,
                  Method disable, Method blendFunc, Method pushMatrix, Method popMatrix,
                  Method translatef, Method scalef, Method lineWidth, Method texCoord2f,
                  Method bindTexture) {
        this.color4f = color4f;
        this.begin = begin;
        this.end = end;
        this.vertex2f = vertex2f;
        this.enable = enable;
        this.disable = disable;
        this.blendFunc = blendFunc;
        this.pushMatrix = pushMatrix;
        this.popMatrix = popMatrix;
        this.translatef = translatef;
        this.scalef = scalef;
        this.lineWidth = lineWidth;
        this.texCoord2f = texCoord2f;
        this.bindTexture = bindTexture;
    }

    /** Loads {@code org.lwjgl.opengl.GL11} through {@code loader} and binds it, or {@code null}. */
    public static GlApi bind(String className, ClassLoader loader) {
        return bind(Reflect.load(className, loader));
    }

    /** Binds an already-resolved GL class (also used by tests with a stand-in). */
    public static GlApi bind(Class<?> gl) {
        if (gl == null) {
            return null;
        }
        Method color4f = Reflect.method(gl, "glColor4f", float.class, float.class, float.class, float.class);
        Method begin = Reflect.method(gl, "glBegin", int.class);
        Method end = Reflect.method(gl, "glEnd");
        Method vertex2f = Reflect.method(gl, "glVertex2f", float.class, float.class);
        if (color4f == null || begin == null || end == null || vertex2f == null) {
            return null; // not a usable GL11
        }
        return new GlApi(
                color4f, begin, end, vertex2f,
                Reflect.method(gl, "glEnable", int.class),
                Reflect.method(gl, "glDisable", int.class),
                Reflect.method(gl, "glBlendFunc", int.class, int.class),
                Reflect.method(gl, "glPushMatrix"),
                Reflect.method(gl, "glPopMatrix"),
                Reflect.method(gl, "glTranslatef", float.class, float.class, float.class),
                Reflect.method(gl, "glScalef", float.class, float.class, float.class),
                Reflect.method(gl, "glLineWidth", float.class),
                Reflect.method(gl, "glTexCoord2f", float.class, float.class),
                Reflect.method(gl, "glBindTexture", int.class, int.class));
    }

    public void color(float r, float g, float b, float a) {
        Reflect.call(color4f, null, r, g, b, a);
    }

    public void begin(int mode) {
        Reflect.call(begin, null, mode);
    }

    public void end() {
        Reflect.call(end, null);
    }

    public void vertex(float x, float y) {
        Reflect.call(vertex2f, null, x, y);
    }

    public void enable(int cap) {
        Reflect.call(enable, null, cap);
    }

    public void disable(int cap) {
        Reflect.call(disable, null, cap);
    }

    public void blendFunc(int src, int dst) {
        Reflect.call(blendFunc, null, src, dst);
    }

    public void pushMatrix() {
        Reflect.call(pushMatrix, null);
    }

    public void popMatrix() {
        Reflect.call(popMatrix, null);
    }

    public void translate(float x, float y, float z) {
        Reflect.call(translatef, null, x, y, z);
    }

    public void scale(float x, float y, float z) {
        Reflect.call(scalef, null, x, y, z);
    }

    public void lineWidth(float width) {
        Reflect.call(lineWidth, null, width);
    }

    public void texCoord(float u, float v) {
        Reflect.call(texCoord2f, null, u, v);
    }

    public void bindTexture(int target, int texture) {
        Reflect.call(bindTexture, null, target, texture);
    }

    /** Convenience: draw a filled rectangle in immediate mode. */
    public void fillRect(float x, float y, float width, float height, float r, float g, float b, float a) {
        color(r, g, b, a);
        begin(GL_QUADS);
        vertex(x, y + height);
        vertex(x + width, y + height);
        vertex(x + width, y);
        vertex(x, y);
        end();
    }

    /** Convenience: draw a 1px-aligned outline rectangle. */
    public void strokeRect(float x, float y, float width, float height, float lineWidth,
                           float r, float g, float b, float a) {
        color(r, g, b, a);
        this.lineWidth(lineWidth);
        begin(GL_LINE_LOOP);
        vertex(x, y);
        vertex(x + width, y);
        vertex(x + width, y + height);
        vertex(x, y + height);
        end();
    }
}
