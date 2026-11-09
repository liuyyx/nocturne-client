package dev.nocturne.client.runtime;

/**
 * 移动键的物理按下状态快照。
 *
 * <p>为什么需要它：Keystrokes 以前只订阅 {@code KeyEvent}，而该事件在原生 Screen 打开时
 * 被有意抑制（按键属于界面），于是「按住 W 开聊天再松开，关掉后仍显示按下」；
 * 1.8.9 的按键钩子缺失时更是全程不亮。UI 层每帧都拿着真实的输入源（LWJGL2/GLFW/SDL），
 * 由它把状态写进这里，HUD 模块只读——与界面是否打开、事件是否投递无关。
 *
 * <p>线程契约：UI 的帧回调与 HUD 文本拉取在同一渲染线程；字段仍是 volatile，
 * 以容忍测试/其它线程的写入，不保证多写者的原子组合。
 */
public final class PhysicalKeys {

    /** 顺序固定：前进、左、后退、右、跳跃（空格）。 */
    private static volatile boolean forward;
    private static volatile boolean left;
    private static volatile boolean back;
    private static volatile boolean right;
    private static volatile boolean jump;
    /** UI 是否至少写入过一次；false 时 HUD 应回退到事件/KeyMapping。 */
    private static volatile boolean available;

    private PhysicalKeys() {
    }

    /**
     * 用本帧的物理按键状态整体替换快照。
     *
     * @param forward 前进
     * @param left    左移
     * @param back    后退
     * @param right   右移
     * @param jump    跳跃（空格）
     */
    public static void update(boolean forward, boolean left, boolean back, boolean right,
                              boolean jump) {
        PhysicalKeys.forward = forward;
        PhysicalKeys.left = left;
        PhysicalKeys.back = back;
        PhysicalKeys.right = right;
        PhysicalKeys.jump = jump;
        available = true;
    }

    /** @return UI 是否已经提供过物理键快照（未安装 UI 时为 false） */
    public static boolean available() {
        return available;
    }

    /**
     * @return 长度为 5 的快照：{@code [前进, 左, 后退, 右, 跳跃]}
     */
    public static boolean[] movement() {
        return new boolean[]{forward, left, back, right, jump};
    }

    /** 清空（模块禁用/测试隔离）。 */
    public static void clear() {
        update(false, false, false, false, false);
        available = false;
    }
}
