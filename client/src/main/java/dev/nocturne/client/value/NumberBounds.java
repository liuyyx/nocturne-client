package dev.nocturne.client.value;

import java.util.Locale;

/** 有序数值区间，始终保持 {@code min <= max}。 */
public final class NumberBounds {

    private final double min;
    private final double max;

    public NumberBounds(double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("bounds must be finite");
        }
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public NumberBounds withMin(double value) {
        return new NumberBounds(Math.min(value, max), max);
    }

    public NumberBounds withMax(double value) {
        return new NumberBounds(min, Math.max(value, min));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof NumberBounds)) {
            return false;
        }
        NumberBounds that = (NumberBounds) other;
        return Double.compare(min, that.min) == 0 && Double.compare(max, that.max) == 0;
    }

    @Override
    public int hashCode() {
        long minBits = Double.doubleToLongBits(min);
        long maxBits = Double.doubleToLongBits(max);
        return (int) ((minBits ^ (minBits >>> 32)) * 31L ^ (maxBits ^ (maxBits >>> 32)));
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%.2f – %.2f", min, max);
    }
}
