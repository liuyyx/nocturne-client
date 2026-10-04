package dev.noturne.client.value;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * 数值设置，取值被钳制在 {@code [min, max]} 内，并对齐到 {@code step} 的整数倍（step 为 0 表示不对齐）。
 *
 * <p>所有写入路径（含默认值与 {@link #reset()}）都经过 {@link #coerce(Double)}，因此实例一旦构造完成，
 * 当前值与默认值必然处于合法区间且已对齐到步长。
 */
public final class NumberValue extends Value<Double> {

    /** 允许的最小值（含）。 */
    private final double min;
    /** 允许的最大值（含）。构造时校验必须严格大于 min。 */
    private final double max;
    /** 对齐步长；为 0 表示不做步进对齐。构造时小于等于 0 的值一律归一为 0。 */
    private final double step;
    /** {@link #min} 的十进制精确表示，构造时缓存，用于消除步长对齐时的二进制误差。 */
    private final BigDecimal minDecimal;
    /** {@link #step} 的十进制精确表示；step 为 0 时为 {@code null}。 */
    private final BigDecimal stepDecimal;

    /**
     * 构造数值设置，并用 {@code coerce} 钳制默认值。
     *
     * @throws IllegalArgumentException 当 {@code max <= min} 时——否则区间为空，任何值都无法归一
     */
    public NumberValue(String name, double defaultValue, double min, double max, double step) {
        super(name, defaultValue);
        if (max <= min) {
            throw new IllegalArgumentException("max must be greater than min");
        }
        this.min = min;
        this.max = max;
        this.step = step <= 0 ? 0 : step;
        this.minDecimal = BigDecimal.valueOf(this.min);
        this.stepDecimal = this.step > 0 ? BigDecimal.valueOf(this.step) : null;
        // 默认值同样过 coerce：否则 reset() 会把越界/未对齐的原始入参写回当前值
        normaliseDefault();
        set(defaultValue());
    }

    /** @return 下界（含） */
    public double min() {
        return min;
    }

    /** @return 上界（含） */
    public double max() {
        return max;
    }

    /** @return 对齐步长；0 表示不对齐 */
    public double step() {
        return step;
    }

    /**
     * @return 四舍五入为整数的当前值，供需要 int 参数的游戏 API 使用；超出 int 值域时钳制在
     *         {@link Integer#MIN_VALUE}/{@link Integer#MAX_VALUE}，不再发生静默回绕
     */
    public int asInt() {
        double v = get();
        if (v <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (v >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(v);
    }

    /** @return 当前值的 float 形式，供 LWJGL 等原生接口使用 */
    public float asFloat() {
        return get().floatValue();
    }

    /** @return 当前值是否大于 0；用于「以数值充当开关」的设置项 */
    public boolean asBoolean() {
        return get() > 0.0;
    }

    /**
     * 把传入值钳制到区间内并按步长对齐。
     *
     * @param newValue 原始值；{@code null} 与 {@code NaN} 都视为下界
     * @return 落在 {@code [min, max]} 内且对齐到步长的值
     */
    @Override
    protected Double coerce(Double newValue) {
        double v = newValue == null ? min : newValue.doubleValue();
        // NaN 与任何数比较都为 false，会同时绕过下面两条钳制，故先显式归一为下界
        if (Double.isNaN(v)) {
            v = min;
        }
        if (v < min) {
            v = min;
        }
        if (v > max) {
            v = max;
        }
        if (step > 0) {
            // 以 min 为基准对齐而非以 0 为基准，这样 min 不是步长整数倍时结果仍严格落在 [min, max]
            long ticks = Math.round((v - min) / step);
            // 用十进制精确表示计算 min + ticks*step，消除 0.1*3=0.30000000000000004 这类二进制误差，
            // 否则 isDefault() 用 Double 相等比较会永远判定为非默认
            v = minDecimal.add(stepDecimal.multiply(BigDecimal.valueOf(ticks))).doubleValue();
            if (v > max) {
                // 向上对齐可能越过 max，需再钳一次，否则会写出越界值
                v = max;
            }
            if (v < min) {
                v = min;
            }
        }
        return v;
    }

    /** 整数取值不显示小数位，否则保留两位小数，避免 GUI 上出现 {@code 3.0} 这类冗余文本。 */
    @Override
    public String display() {
        double v = get();
        // 固定 Locale.ROOT：否则 de_DE/fr_FR 下会输出「3,50」，与 coerce 使用的 '.' 语义冲突
        return String.format(Locale.ROOT, v == Math.rint(v) ? "%.0f" : "%.2f", v);
    }
}
