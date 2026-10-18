package dev.nocturne.ui.gl;

import java.awt.event.KeyEvent;

/**
 * 把「哪些键被按下」翻译成输入字符。
 *
 * <p>为什么需要它：本叠加层是<b>轮询</b>模型（见 {@link InputSource}），两代 LWJGL 与 SDL 的字符
 * 回调签名各不相同，而界面里的搜索框、数值输入与 HEX 输入必须拿到真实字符。与其为三套输入栈各写
 * 一个回调适配，不如在轮询模型里自己把「新按下的可打印键」合成字符：键码统一是 AWT VK，Shift 决定
 * 大小写与符号，一份表管所有版本。
 *
 * <p>只覆盖界面用得到的键（字母、数字、常用符号与空格）。其余键不产生字符——猜一个错的字符会让
 * 输入框里出现莫名其妙的符号，比不产生更糟。
 */
final class KeyChars {

    /** 需要轮询的可打印键（AWT VK 码），顺序无关。 */
    private static final int[] KEYS = {
            KeyEvent.VK_A, KeyEvent.VK_B, KeyEvent.VK_C, KeyEvent.VK_D, KeyEvent.VK_E,
            KeyEvent.VK_F, KeyEvent.VK_G, KeyEvent.VK_H, KeyEvent.VK_I, KeyEvent.VK_J,
            KeyEvent.VK_K, KeyEvent.VK_L, KeyEvent.VK_M, KeyEvent.VK_N, KeyEvent.VK_O,
            KeyEvent.VK_P, KeyEvent.VK_Q, KeyEvent.VK_R, KeyEvent.VK_S, KeyEvent.VK_T,
            KeyEvent.VK_U, KeyEvent.VK_V, KeyEvent.VK_W, KeyEvent.VK_X, KeyEvent.VK_Y,
            KeyEvent.VK_Z,
            KeyEvent.VK_0, KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4,
            KeyEvent.VK_5, KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9,
            KeyEvent.VK_SPACE, KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS, KeyEvent.VK_COMMA,
            KeyEvent.VK_PERIOD, KeyEvent.VK_SLASH, KeyEvent.VK_SEMICOLON, KeyEvent.VK_QUOTE,
            KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_BACK_SLASH,
    };

    /** 需要 Shift 的符号键对应表：与 {@link #SHIFTED} 一一对应。 */
    private static final int[] PLAIN = {
            KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS, KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD,
            KeyEvent.VK_SLASH, KeyEvent.VK_SEMICOLON, KeyEvent.VK_QUOTE, KeyEvent.VK_OPEN_BRACKET,
            KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_BACK_SLASH,
    };

    /** {@link #PLAIN} 中每个键在按住 Shift 时产生的字符。 */
    private static final char[] SHIFTED = {'_', '+', '<', '>', '?', ':', '"', '{', '}', '|'};

    private KeyChars() {
    }

    /** @return 需要轮询的可打印键码表（调用方按同一下标维护"上一帧是否按下"） */
    static int[] keys() {
        return KEYS;
    }

    /**
     * 把一个键码翻译成字符。
     *
     * @param key   AWT VK 码
     * @param shift 是否按住 Shift
     * @return 对应字符；该键不产生字符时返回 {@code 0}
     */
    static char charFor(int key, boolean shift) {
        if (key >= KeyEvent.VK_A && key <= KeyEvent.VK_Z) {
            char lower = (char) ('a' + (key - KeyEvent.VK_A));
            return shift ? Character.toUpperCase(lower) : lower;
        }
        if (key >= KeyEvent.VK_0 && key <= KeyEvent.VK_9) {
            if (!shift) {
                return (char) ('0' + (key - KeyEvent.VK_0));
            }
            // Shift + 数字键的标准（US 布局）符号。
            return ")!@#$%^&*(".charAt(key - KeyEvent.VK_0);
        }
        if (key == KeyEvent.VK_SPACE) {
            return ' ';
        }
        for (int i = 0; i < PLAIN.length; i++) {
            if (PLAIN[i] == key) {
                return shift ? SHIFTED[i] : plainChar(key);
            }
        }
        return 0;
    }

    /** @return 未按 Shift 时符号键的字符 */
    private static char plainChar(int key) {
        switch (key) {
            case KeyEvent.VK_MINUS:
                return '-';
            case KeyEvent.VK_EQUALS:
                return '=';
            case KeyEvent.VK_COMMA:
                return ',';
            case KeyEvent.VK_PERIOD:
                return '.';
            case KeyEvent.VK_SLASH:
                return '/';
            case KeyEvent.VK_SEMICOLON:
                return ';';
            case KeyEvent.VK_QUOTE:
                return '\'';
            case KeyEvent.VK_OPEN_BRACKET:
                return '[';
            case KeyEvent.VK_CLOSE_BRACKET:
                return ']';
            case KeyEvent.VK_BACK_SLASH:
                return '\\';
            default:
                return 0;
        }
    }
}
