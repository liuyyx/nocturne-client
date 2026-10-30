package dev.nocturne.client.value;

/** AWT VK 键码设置；0 表示未绑定，负数和 {@code null} 也归为未绑定。 */
public final class KeyValue extends Value<Integer> {

    public static final int NONE = 0;

    public KeyValue(String name, int defaultKey) {
        super(name, defaultKey);
        set(defaultValue());
        normaliseDefault();
    }

    public boolean bound() {
        return get() != NONE;
    }

    @Override
    protected Integer coerce(Integer newValue) {
        return newValue == null ? NONE : Math.max(NONE, newValue);
    }

    @Override
    public String display() {
        return bound() ? java.awt.event.KeyEvent.getKeyText(get()) : "None";
    }
}
