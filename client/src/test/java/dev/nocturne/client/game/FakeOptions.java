package dev.nocturne.client.game;

/** 替身现代 Options：{@code gamma} 是 {@code OptionInstance} 型字段（26.x）。 */
public final class FakeOptions {

    /** {@code Options.gamma} 对应字段 */
    public FakeOptionInstance gamma;

    /** @param initial 初始 gamma 数值 */
    public FakeOptions(double initial) {
        this.gamma = new FakeOptionInstance(initial);
    }
}
