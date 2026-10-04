package org.lwjgl.opengl;

/** 测试专用替身：模拟 LWJGL2 的 {@code org.lwjgl.opengl.Display}，提供窗口尺寸查询。 */
public final class Display {

    private Display() {
    }

    public static int getWidth() {
        return 800;
    }

    public static int getHeight() {
        return 600;
    }
}
