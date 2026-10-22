package dev.nocturne.bootstrap;

/**
 * 按键事件汇：<b>只存在于 bootstrap 搜索路径</b>（由 agent 的 bridge jar 提供），agent jar 里没有这个类。
 *
 * <p>为什么必须这样：{@code InputBlock} 同时被 agent jar 与 bootstrap 各加载了一份——Fabric 的
 * {@code KnotClassLoader} 会把 agent jar 也加载一遍，于是织入的 {@code recordKey} 写进 Knot 那份，
 * 而叠加层经 bootstrap 读到的是另一份（1.21.1 实测：两侧 class identity 不同，consumePressed 恒 false）。
 *
 * <p>本类不依赖任何项目类，只依赖 JDK；调用方（织入的字节码与叠加层）都用
 * {@code Class.forName(..., true, null)} 或 bootstrap 可见的名字解析，因此全局只有一份。
 */
public final class KeySink {

    /** 最近一次按下的 GLFW 键码（-1 表示没有新按键）。 */
    private static volatile int lastPressedKey = -1;
    /** 最近一次按下的时间戳（纳秒）。 */
    private static volatile long lastPressedAt;

    private KeySink() {
    }

    /**
     * 记录一次按键（由织入 {@code KeyboardHandler.keyPress} 的字节码调用）。
     *
     * @param glfwKey GLFW 键码
     * @param action  GLFW 动作（1 = 按下）
     */
    public static void recordKey(int glfwKey, int action) {
        if (action == 1) {
            lastPressedKey = glfwKey;
            lastPressedAt = System.nanoTime();
        }
    }

    /**
     * 取出并清除一次「刚按下」的按键。
     *
     * @param glfwKey     期望的 GLFW 键码
     * @param windowNanos 有效窗口（超过视为过期）
     * @return 命中返回 {@code true}
     */
    public static boolean consumePressed(int glfwKey, long windowNanos) {
        if (lastPressedKey != glfwKey) {
            return false;
        }
        if (System.nanoTime() - lastPressedAt > windowNanos) {
            return false;
        }
        lastPressedKey = -1;
        return true;
    }
}
