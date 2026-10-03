package dev.noturne.ui.gl;

/** Stand-in for {@code org.lwjgl.opengl.GL11} that records immediate-mode traffic. */
public final class FakeGl {

    public static int colorCalls;
    public static int beginCalls;
    public static int endCalls;
    public static int vertexCalls;
    public static int lineWidthCalls;
    public static int lastBeginMode = -1;
    public static float lastLineWidth = -1f;

    private FakeGl() {
    }

    public static void reset() {
        colorCalls = 0;
        beginCalls = 0;
        endCalls = 0;
        vertexCalls = 0;
        lineWidthCalls = 0;
        lastBeginMode = -1;
        lastLineWidth = -1f;
    }

    public static void glColor4f(float r, float g, float b, float a) {
        colorCalls++;
    }

    public static void glBegin(int mode) {
        beginCalls++;
        lastBeginMode = mode;
    }

    public static void glEnd() {
        endCalls++;
    }

    public static void glVertex2f(float x, float y) {
        vertexCalls++;
    }

    public static void glLineWidth(float width) {
        lineWidthCalls++;
        lastLineWidth = width;
    }

    public static void glEnable(int cap) {
    }

    public static void glDisable(int cap) {
    }

    public static void glBlendFunc(int src, int dst) {
    }

    public static void glPushMatrix() {
    }

    public static void glPopMatrix() {
    }

    public static void glTranslatef(float x, float y, float z) {
    }

    public static void glScalef(float x, float y, float z) {
    }

    public static void glTexCoord2f(float u, float v) {
    }

    public static void glBindTexture(int target, int texture) {
    }
}
