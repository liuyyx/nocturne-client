package dev.noturne.client.value;

/**
 * Numeric setting clamped to {@code [min, max]} and snapped to {@code step} (0 = no snapping).
 */
public final class NumberValue extends Value<Double> {

    private final double min;
    private final double max;
    private final double step;

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

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public double step() {
        return step;
    }

    public int asInt() {
        return (int) Math.round(get());
    }

    public float asFloat() {
        return get().floatValue();
    }

    public boolean asBoolean() {
        return get() > 0.0;
    }

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
            v = min + Math.round((v - min) / step) * step;
            if (v > max) {
                v = max;
            }
        }
        return v;
    }

    @Override
    public String display() {
        double v = get();
        if (v == Math.rint(v)) {
            return Integer.toString((int) v);
        }
        return String.format("%.2f", v);
    }
}
