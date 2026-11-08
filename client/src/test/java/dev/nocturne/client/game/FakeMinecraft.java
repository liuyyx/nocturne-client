package dev.nocturne.client.game;

/**
 * 替身「游戏主类」：提供与 {@code Minecraft.getInstance()} 同形的静态访问器，
 * 以及模块会读取的 {@code player}/{@code options} 字段。
 */
public final class FakeMinecraft {

    /** 当前替身单例；测试可改写其字段 */
    public static FakeMinecraft instance = new FakeMinecraft();

    /** {@code Minecraft.player} 对应字段 */
    public Object player;
    /** {@code Minecraft.options} 对应字段 */
    public Object options;
    /** {@code Minecraft.gui} 对应字段（26.x 的当前界面挂在 {@code Gui.screen()} 上） */
    public Object gui;
    /** {@code Minecraft.level} 对应字段 */
    public Object level;

    private FakeMinecraft() {
    }

    /** 重置为全新单例 */
    public static void reset() {
        instance = new FakeMinecraft();
    }

    /** @return 替身单例，对应 {@code Minecraft.getInstance()} */
    public static FakeMinecraft getInstance() {
        return instance;
    }
}
