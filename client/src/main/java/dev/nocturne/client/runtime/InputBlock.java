package dev.nocturne.client.runtime;

/**
 * 我们的界面是否占用输入。
 *
 * <p><b>为什么必须放在 bootstrap 层</b>（与 {@link FrameDispatcher} 同样的理由）：这个类有两类
 * 调用方，分属两个类加载器——一是游戏自己的输入入口（织入 {@code MouseHandler.onButton} /
 * {@code KeyboardHandler.keyPress} 的字节码里），二是我们的叠加层。类若落在别处，两边会各持一份
 * 静态状态，织入的短路永远读到 {@code false}，症状是"改了没用"。
 *
 * <p><b>为什么需要它</b>：我们的界面不是 vanilla {@code Screen}，MC 的输入派发不知道我们吃掉了
 * 这次点击/按键，于是同一次点击**同时**打在后面的原生界面上（点模块会顺带按到「回到游戏」这类
 * 按钮，Esc 会顺带打开暂停菜单）。界面打开期间把游戏侧输入入口短路掉即可。
 */
public final class InputBlock {

    /** 我们的界面是否正在占用输入；游戏侧输入入口据此短路。 */
    private static volatile boolean blocked;

    private InputBlock() {
    }

    /**
     * 设置占用状态（由叠加层每帧调用）。
     *
     * @param value {@code true} = 我们的界面对着，游戏侧输入入口应短路
     */
    public static void setBlocked(boolean value) {
        blocked = value;
    }

    /**
     * @return 游戏侧输入入口是否应短路（由织入的字节码调用）
     */
    public static boolean shouldBlock() {
        return blocked;
    }
}
