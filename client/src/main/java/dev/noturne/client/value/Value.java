package dev.noturne.client.value;

/**
 * A named, typed setting owned by a module.
 *
 * <p>Values carry their default so the GUI can offer "reset to default" and the config layer can
 * detect what needs persisting.
 */
public abstract class Value<T> {

    private final String name;
    private final T defaultValue;
    private T value;

    protected Value(String name, T defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    public String name() {
        return name;
    }

    public T get() {
        return value;
    }

    /** Sets the value, normalised by {@link #coerce}. */
    public void set(T newValue) {
        this.value = coerce(newValue);
    }

    public T defaultValue() {
        return defaultValue;
    }

    public void reset() {
        this.value = defaultValue;
    }

    public boolean isDefault() {
        return defaultValue == null ? value == null : defaultValue.equals(value);
    }

    /** Normalises an incoming value (clamping, option lookup). Default: identity. */
    protected T coerce(T newValue) {
        return newValue;
    }

    /** Short human-readable rendering used by the GUI, e.g. {@code "12.5"} or {@code "Toggle"}. */
    public abstract String display();

    @Override
    public String toString() {
        return name + "=" + display();
    }
}
