package dev.noturne.client.game;

/** 替身 {@code OptionInstance}：现代版本 gamma 字段的类型，内部持有数值并接受 {@code force_double}。 */
public final class FakeOptionInstance {

    /** 当前数值 */
    public double value;

    /** @param value 初始数值 */
    public FakeOptionInstance(double value) {
        this.value = value;
    }

    /** 模拟 {@code OptionInstance.get()}（返回 Number） */
    public Double get() {
        return value;
    }

    /** 模拟绕过取值校验器的写入入口 */
    public void force_double(double newValue) {
        this.value = newValue;
    }
}
