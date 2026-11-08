package dev.nocturne.client.game;

/** 替身 {@code Component}：只提供 {@code literal(String)} 静态工厂。 */
public final class FakeComponent {

    private final String text;

    private FakeComponent(String text) {
        this.text = text;
    }

    /** 对应 {@code Component.literal(String)}。 */
    public static Object literal(String text) {
        return new FakeComponent(text);
    }

    @Override
    public String toString() {
        return text;
    }
}
