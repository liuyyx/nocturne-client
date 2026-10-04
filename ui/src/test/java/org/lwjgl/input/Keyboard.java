package org.lwjgl.input;

/** 测试专用替身：模拟 LWJGL2 的 {@code org.lwjgl.input.Keyboard}。 */
public final class Keyboard {

    private Keyboard() {
    }

    public static boolean isKeyDown(int key) {
        return false;
    }
}
