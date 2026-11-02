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
    /** 我们的界面是否正在占用输入；游戏侧输入入口据此短路。 */
    private static volatile boolean blocked;
    /** 是否已永久停止占用输入（自销毁）。 */
    private static volatile boolean disabled;
    /** 滚轮累加器与读取之间的锁。 */
    private static final Object SCROLL_LOCK = new Object();
    /** 自上次取走以来累积的滚轮增量；放这里是因为本类全局只有 bootstrap 一份。 */
    private static double pendingScroll;

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
            lastPressedKey = -1;
            return false;
        }
        lastPressedKey = -1;
        return true;
    }

    /**
     * 设置我们的界面是否占用输入。
     *
     * @param value {@code true} = 游戏侧输入入口应短路
     */
    public static void setBlocked(boolean value) {
        blocked = value;
    }

    /** 永久停止占用输入（自销毁时调用），此后一律放行。 */
    public static void disable() {
        disabled = true;
        blocked = false;
    }

    /** @return 游戏侧输入入口是否应短路 */
    public static boolean shouldBlock() {
        return !disabled && blocked;
    }

    /**
     * 累加一次滚轮增量。
     *
     * <p>与按键一样，状态必须放在 bootstrap 唯一的一份 {@code KeySink}；Knot 会再加载一份
     * {@code ScrollState}，但两边最终都转发到这里。
     *
     * @param delta 增量；{@code 0}、NaN、无穷会被忽略
     */
    public static void accumulateScroll(double delta) {
        if (delta == 0d || Double.isNaN(delta) || Double.isInfinite(delta)) {
            return;
        }
        synchronized (SCROLL_LOCK) {
            pendingScroll += delta;
        }
    }

    /**
     * 取走并清零滚轮增量。
     *
     * @return 自上次取走以来的增量；没有时返回 {@code 0}
     */
    public static double takeScroll() {
        synchronized (SCROLL_LOCK) {
            double value = pendingScroll;
            pendingScroll = 0d;
            return value;
        }
    }

    /** 清空滚轮增量（自销毁等场景）。 */
    public static void resetScroll() {
        synchronized (SCROLL_LOCK) {
            pendingScroll = 0d;
        }
    }
}
