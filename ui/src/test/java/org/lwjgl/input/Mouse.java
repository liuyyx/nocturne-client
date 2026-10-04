package org.lwjgl.input;

/**
 * 测试专用替身：模拟 LWJGL2 的 {@code org.lwjgl.input.Mouse}，使 {@code ReflectiveInput}
 * 能在无原生输入的单元测试里走 LWJGL2 分支（滚轮归一化 H-25）。
 */
public final class Mouse {

    /** 光标 x（窗口坐标） */
    public static int x;
    /** 光标 y（窗口坐标，原点左下） */
    public static int y;
    /** 下一次 {@link #getDWheel()} 返回的滚轮增量，取后清零 */
    public static int dWheel;
    /** 最近一次 {@link #setGrabbed(boolean)} 的状态 */
    public static boolean grabbed;
    /** {@link #setGrabbed(boolean)} 被调用次数 */
    public static int grabbedCalls;

    private Mouse() {
    }

    public static int getX() {
        return x;
    }

    public static int getY() {
        return y;
    }

    public static boolean isButtonDown(int button) {
        return false;
    }

    public static int getDWheel() {
        int value = dWheel;
        dWheel = 0;
        return value;
    }

    public static void setGrabbed(boolean grab) {
        grabbed = grab;
        grabbedCalls++;
    }
}
