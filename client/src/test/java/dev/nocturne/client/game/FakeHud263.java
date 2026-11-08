package dev.nocturne.client.game;

/**
 * 替身 26.x {@code net.minecraft.client.gui.Hud}：聊天框挂在它自己身上。
 *
 * <p>26.x 的 {@code Minecraft.gui} 运行时类型就是 Hud（不是 Gui），
 * {@code Gui#getChat} 在这些版本是 verified absent。
 */
public final class FakeHud263 {

    /** {@code Hud.chat} 字段。 */
    public Object chat;
    /** {@code getChat()} 被调用的次数（验证没有走 Gui 入口）。 */
    public int getChatCalls;

    /** 对应 {@code Hud.getChat()}。 */
    public Object getChat() {
        getChatCalls++;
        return chat;
    }
}
