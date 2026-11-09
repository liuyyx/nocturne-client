package dev.nocturne.client.game;

/** 替身 1.8.9 聊天包 {@code C01PacketChatMessage(String)}。 */
public final class FakeChatPacket {

    private final String message;

    public FakeChatPacket(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
