package dev.nocturne.client.runtime;

import dev.nocturne.bootstrap.KeySink;

/**
 * 滚轮增量的中转站（SDL 世代）。
 *
 * <p><b>为什么需要中转</b>：SDL 的滚轮是事件驱动的，没有轮询接口——叠加层的输入却是每帧
 * 轮询的。事件钩子在游戏自己的事件入口上把它累加，界面每帧取走即可。
 *
 * <p><b>为什么状态不放在本类</b>：26.3 的 KnotClassLoader 会给 {@code ScrollHook} 再加载
 * 一份本类，而叠加层读的是 bootstrap 那份；静态字段因此分成两套（实测 hook 与 UI 的
 * {@code ScrollState} class identity 不同）。真正的状态放在只由 bootstrap 提供的
 * {@link KeySink}，本类只做 API 转发，所有加载器就都读写同一份。
 */
public final class ScrollState {

    private ScrollState() {
    }

    /**
     * 累加一次滚轮增量（由事件钩子调用）。
     *
     * @param delta 增量；{@code 0}、NaN、无穷会被忽略
     */
    public static void accumulate(double delta) {
        KeySink.accumulateScroll(delta);
    }

    /**
     * 取走并清零累积的增量（界面每帧调用；界面关闭期间也必须调用，否则打开瞬间会把积压一次滚出来）。
     *
     * @return 自上次取走以来的增量；没有时返回 {@code 0}
     */
    public static double take() {
        return KeySink.takeScroll();
    }

    /** 清空（自销毁等场景用）。 */
    public static void reset() {
        KeySink.resetScroll();
    }

    /**
     * 标记游戏自己的滚轮入口已被织入累加钩子（由织入方调用）。
     *
     * <p>置位后叠加层不再注册 GLFW 滚轮回调：那个接口是单槽位的，顶掉之后游戏自己的
     * {@code MouseHandler#onScroll} 再也不会被调用，滚轮会彻底失效。
     */
    public static void markGameHook() {
        KeySink.markScrollHook();
    }

    /** @return 游戏自己的滚轮入口是否已被织入累加钩子 */
    public static boolean gameHookPresent() {
        return KeySink.scrollHookActive();
    }
}
