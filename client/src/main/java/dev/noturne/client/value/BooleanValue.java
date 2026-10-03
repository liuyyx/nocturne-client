package dev.noturne.client.value;

/** On/off setting. */
public final class BooleanValue extends Value<Boolean> {

    public BooleanValue(String name, boolean defaultValue) {
        super(name, defaultValue);
    }

    public void toggle() {
        set(!get());
    }

    @Override
    protected Boolean coerce(Boolean newValue) {
        return newValue == null ? Boolean.FALSE : newValue;
    }

    @Override
    public String display() {
        return get() ? "On" : "Off";
    }
}
