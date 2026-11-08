package dev.nocturne.client.game;

/**
 * 替身「没有 literal 的 Component」：模拟映射表缺失 {@code Component#literal} 的版本
 * （如 1.20.1 表未命中），只保留 {@code empty()} + {@code append(String)} 的兜底入口。
 */
public final class FakeComponentWithoutLiteral {

    private final StringBuilder text = new StringBuilder();

    /** 对应 {@code Component.empty()}。 */
    public static Object empty() {
        return new FakeComponentWithoutLiteral();
    }

    /** 对应 {@code MutableComponent#append(String)}。 */
    public FakeComponentWithoutLiteral append(String value) {
        text.append(value);
        return this;
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
