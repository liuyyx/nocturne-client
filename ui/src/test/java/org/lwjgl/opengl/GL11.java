package org.lwjgl.opengl;

import java.util.HashSet;
import java.util.Set;

/** ModernRenderer 原生库存模型测试使用的 GL11 固定管线替身。 */
public final class GL11 {
    public static final int GL_MODELVIEW = 0x1700;
    public static final int GL_PROJECTION = 0x1701;
    public static final int GL_TEXTURE0 = 0x84C0;

    public static int matrixMode;
    public static int activeTexture;
    public static int boundTexture;
    public static int pushMatrixCalls;
    public static int popMatrixCalls;
    public static double depthNear;
    public static double depthFar;
    public static double[] lastOrtho;
    public static final Set<Integer> enabledCaps = new HashSet<Integer>();

    private GL11() {
    }

    public static void reset() {
        matrixMode = GL_MODELVIEW;
        activeTexture = GL_TEXTURE0;
        boundTexture = 17;
        pushMatrixCalls = 0;
        popMatrixCalls = 0;
        depthNear = 0.1;
        depthFar = 0.9;
        lastOrtho = null;
        enabledCaps.clear();
        enabledCaps.add(0x0BE2); // ModernRenderer.beginFrame 的混合状态
    }

    public static boolean isEnabled(int cap) {
        return enabledCaps.contains(cap);
    }

    public static void glColor4f(float r, float g, float b, float a) {
    }

    public static void glBegin(int mode) {
    }

    public static void glEnd() {
    }

    public static void glVertex2f(float x, float y) {
    }

    public static void glEnable(int cap) {
        enabledCaps.add(cap);
    }

    public static void glDisable(int cap) {
        enabledCaps.remove(cap);
    }

    public static boolean glIsEnabled(int cap) {
        return enabledCaps.contains(cap);
    }

    public static void glBlendFunc(int src, int dst) {
    }

    public static void glPushMatrix() {
        pushMatrixCalls++;
    }

    public static void glPopMatrix() {
        popMatrixCalls++;
    }

    public static void glMatrixMode(int mode) {
        matrixMode = mode;
    }

    public static void glLoadIdentity() {
    }

    public static void glOrtho(double left, double right, double bottom, double top,
                               double near, double far) {
        lastOrtho = new double[]{left, right, bottom, top, near, far};
    }

    public static void glGetIntegerv(int name, int[] out) {
        if (name == 0x0BA0) {
            out[0] = matrixMode;
        } else if (name == 0x84E0) {
            out[0] = activeTexture;
        } else if (name == 0x8069) {
            out[0] = boundTexture;
        } else if (name == 0x0BA2 && out.length >= 4) {
            out[0] = 0;
            out[1] = 0;
            out[2] = 100;
            out[3] = 100;
        }
    }

    public static void glGetDoublev(int name, double[] out) {
        if (name == 0x0B70) {
            out[0] = depthNear;
            out[1] = depthFar;
        }
    }

    public static void glDepthRange(double near, double far) {
        depthNear = near;
        depthFar = far;
    }

    public static void glActiveTexture(int unit) {
        activeTexture = unit;
    }

    public static void glBindTexture(int target, int texture) {
        boundTexture = texture;
    }

    public static void glScissor(int x, int y, int width, int height) {
    }
}
