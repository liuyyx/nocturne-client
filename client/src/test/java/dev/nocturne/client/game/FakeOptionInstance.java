package dev.nocturne.client.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 替身 {@code OptionInstance}：现代版本 gamma 字段的类型，内部持有数值并接受 {@code force_double}。
 *
 * <p>与 26.x 一致：{@code set} 对 0..1 之外的值抛 {@code Illegal option value}——真机上这条
 * 异常正是“先写 10.0 再钳制”写在日志里刷了 18 条的原因。所有写入尝试都记录在 {@link #attempts()}，
 * 供测试断言第一次下发的值就已钳制。
 */
public final class FakeOptionInstance {

    /** 当前数值 */
    public double value;

    /** 每次写入尝试的原始值（含将被拒绝的越界值）。 */
    private final List<Double> attempts = new ArrayList<Double>();

    /** @param value 初始数值 */
    public FakeOptionInstance(double value) {
        this.value = value;
    }

    /** 模拟 {@code OptionInstance.get()}（返回 Number） */
    public Double get() {
        return value;
    }

    /** 模拟 {@code OptionInstance.set(T)}：越界值直接拒绝，绝不静默接受。 */
    public void set(Double newValue) {
        attempts.add(newValue);
        if (newValue < 0.0 || newValue > 1.0) {
            throw new IllegalArgumentException("Illegal option value " + newValue + " for 亮度");
        }
        this.value = newValue;
    }

    /** 模拟绕过取值校验器的写入入口 */
    public void force_double(double newValue) {
        attempts.add(newValue);
        this.value = newValue;
    }

    /** @return 按发生顺序记录的写入尝试值 */
    public List<Double> attempts() {
        return Collections.unmodifiableList(attempts);
    }
}
