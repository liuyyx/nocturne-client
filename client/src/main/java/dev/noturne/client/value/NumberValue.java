package dev.noturne.client.value;

/**
 * 数值设置，取值被钳制在 {@code [min, max]} 内，并对齐到 {@code step} 的整数倍（step 为 0 表示不对齐）。
 *
 * <p>所有写入路径（含构造时的默认值）都经过 {@link #coerce(Double)}，因此实例一旦构造完成，
 * 当前值必然处于合法区间。
 */
public final class NumberValue extends Value<Double> {

    /** 允许的最小值（含）。 */
    private final double min;
    /** 允许的最大值（含）。构造时校验必须严格大于 min。 */
    private final double max;
    /** 对齐步长；为 0 表示不做步进对齐。构造时小于等于 0 的值一律归一为 0。 */
    private final double step;

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
        set(defaultValue);
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

    /** @return 四舍五入为整数的当前值，供需要 int 参数的游戏 API 使用 */
    public int asInt() {
        return (int) Math.round(get());
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
     * @param newValue 原始值；{@code null} 视为下界
     * @return 落在 {@code [min, max]} 内且对齐到步长的值
     */
    @Override
    protected Double coerce(Double newValue) {
        double v = newValue == null ? min : newValue;
        if (v < min) {
            v = min;
        }
        if (v > max) {
            v = max;
        }
        if (step > 0) {
            // 以 min 为基准对齐而非以 0 为基准，这样 min 不是步长整数倍时结果仍严格落在 [min, max]
            v = min + Math.round((v - min) / step) * step;
            if (v > max) {
                // 向上对齐可能越过 max，需再钳一次，否则会写出越界值
                v = max;
            }
        }
        return v;
    }

    /** 整数取值不显示小数位，否则保留两位小数，避免 GUI 上出现 {@code 3.0} 这类冗余文本。 */
    @Override
    public String display() {
        double v = get();
        if (v == Math.rint(v)) {
            return Integer.toString((int) v);
        }
        return String.format("%.2f", v);
    }
}
