package dev.nocturne.client.value;

/**
 * 双端数值区间设置。两端共用同一取值范围和步长，写入后始终有序。
 * 规则复用 {@link NumberValue}，避免与单值设置出现不同的钳制/步进语义。
 */
public final class BoundsNumberValue extends Value<NumberBounds> {

    private final NumberValue lowerRule;
    private final NumberValue upperRule;

    public BoundsNumberValue(String name, NumberBounds defaultValue,
                             double min, double max, double step) {
        super(name, defaultValue);
        if (defaultValue == null) {
            throw new IllegalArgumentException("defaultValue == null");
        }
        lowerRule = new NumberValue("lower", defaultValue.min(), min, max, step);
        upperRule = new NumberValue("upper", defaultValue.max(), min, max, step);
        set(defaultValue());
        normaliseDefault();
    }

    public double min() {
        return lowerRule.min();
    }

    public double max() {
        return lowerRule.max();
    }

    public double step() {
        return lowerRule.step();
    }

    @Override
    protected NumberBounds coerce(NumberBounds newValue) {
        if (newValue == null) {
            return defaultValue() == null ? new NumberBounds(min(), max()) : defaultValue();
        }
        lowerRule.set(newValue.min());
        upperRule.set(newValue.max());
        return new NumberBounds(lowerRule.get(), upperRule.get());
    }

    @Override
    public String display() {
        return get().toString();
    }
}
