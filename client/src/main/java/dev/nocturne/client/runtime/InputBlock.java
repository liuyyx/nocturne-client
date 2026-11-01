package dev.nocturne.client.runtime;

import dev.nocturne.bootstrap.KeySink;

/**
 * 我们的界面是否占用输入。
 *
 * <p><b>真正的状态在 bootstrap 的 {@link KeySink}</b>：26.3 的 KnotClassLoader 会给游戏侧输入
 * 入口再加载一份本类，而叠加层写的是另一份；静态字段分裂后织入的短路永远读到 {@code false}，
 * 表现就是“界面开着，点一下鼠标却被游戏抓回中心”。本类只保留 API 转发，所有加载器读写同一份。
 *
 * <p><b>为什么需要它</b>：我们的界面不是 vanilla {@code Screen}，MC 的输入派发不知道我们吃掉了
 * 这次点击/按键，于是同一次点击**同时**打在后面的原生界面上（点模块会顺带按到「回到游戏」这类
 * 按钮，Esc 会顺带打开暂停菜单）。界面打开期间把游戏侧输入入口短路掉即可。
 */
public final class InputBlock {

    private InputBlock() {
    }

    /**
     * 记录一次按键（由织入 {@code KeyboardHandler.keyPress} 的字节码调用）。
     *
     * @param glfwKey GLFW 键码
     * @param action  GLFW 动作（1 = 按下）
     */
    public static void recordKey(int glfwKey, int action) {
        KeySink.recordKey(glfwKey, action);
    }

    /**
     * 取出并清除一次「刚按下」的按键。
     *
     * @param glfwKey     期望的 GLFW 键码
     * @param windowNanos 有效窗口（超过则视为过期，避免界面打开瞬间消费到很久以前的按键）
     * @return 命中返回 {@code true}
     */
    public static boolean consumePressed(int glfwKey, long windowNanos) {
        return KeySink.consumePressed(glfwKey, windowNanos);
    }

    /**
     * 设置占用状态（由叠加层每帧调用）。
     *
     * @param value {@code true} = 我们的界面对着，游戏侧输入入口应短路
     */
    public static void setBlocked(boolean value) {
        KeySink.setBlocked(value);
    }

    /**
     * 永久停止占用输入（自销毁时调用）：此后无论 {@link #setBlocked} 写过什么，一律放行。
     *
     * <p>幂等；可在任意线程调用。
     */
    public static void disable() {
        KeySink.disable();
    }

    /**
     * @return 游戏侧输入入口是否应短路（由织入的字节码调用）
     */
    public static boolean shouldBlock() {
        return KeySink.shouldBlock();
    }
}
