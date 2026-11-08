package dev.nocturne.client.game;

/**
 * 替身 {@code net.minecraft.client.gui.Gui}：只提供桥接层会用到的成员。
 *
 * <p>{@code screen()} 对应 26.x 的「当前界面挂在 Gui 上」，{@code displayClientMessage} 对应
 * 1.20–1.21.x 的本地播报，{@code getChat()} 对应 1.19+/26.x 的聊天入口。
 */
public final class FakeGui {

    /** {@code Gui.screen()} 的返回值。 */
    public Object screen;
    /** 是否走 1.20–1.21 的 {@code displayClientMessage} 入口。 */
    public boolean displayClientMessageSupported;
    /** 已通过 {@code displayClientMessage} 发出的消息。 */
    public Object displayed;
    /** {@code getChat()} 返回的聊天对象；null 表示该形态不存在。 */
    public Object chat;

    /** 对应 {@code Gui.screen()}。 */
    public Object screen() {
        return screen;
    }

    /** 对应 {@code Gui.displayClientMessage(Component, boolean)}。 */
    public void displayClientMessage(Object component, boolean overlay) {
        if (!displayClientMessageSupported) {
            throw new IllegalStateException("not supported by this fake");
        }
        displayed = component;
    }

    /** 对应 {@code Gui.getChat()}。 */
    public Object getChat() {
        return chat;
    }
}
