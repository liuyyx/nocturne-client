package dev.nocturne.client.game;

/**
 * 替身 26.x {@code ChatComponent}：只有公开的 {@code addClientSystemMessage(Component)}。
 *
 * <p>{@code addMessage} 在 26.x 是 private（旧路径拿不到），因此这个替身刻意不提供它。
 */
public final class FakeChatComponent263 {

    /** 已写入的消息条数。 */
    public int messages;
    /** 最后写入的组件。 */
    public Object last;

    /** 对应 {@code ChatComponent.addClientSystemMessage(Component)}。 */
    public void addClientSystemMessage(Object component) {
        messages++;
        last = component;
    }
}
