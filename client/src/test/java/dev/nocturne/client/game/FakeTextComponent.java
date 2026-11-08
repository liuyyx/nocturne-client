package dev.nocturne.client.game;

/**
 * 替身「可构造文本组件」：对应 1.8.9 的 {@code ChatComponentText(String)}。
 *
 * <p>1.8.9–1.12 的 {@code IChatComponent} 是接口，本地播报必须 new 出具体实现。
 */
public final class FakeTextComponent {

    private final String text;

    public FakeTextComponent(String text) {
        this.text = text;
    }

    @Override
    public String toString() {
        return text;
    }
}
