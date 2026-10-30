package dev.nocturne.client.value;

/** 原版快捷栏槽位设置；模型使用 0–8，GUI 显示 1–9。 */
public final class SlotValue extends Value<Integer> {

    public static final int SLOTS = 9;

    public SlotValue(String name, int defaultSlot) {
        super(name, defaultSlot);
        set(defaultValue());
        normaliseDefault();
    }

    @Override
    protected Integer coerce(Integer newValue) {
        if (newValue == null) {
            return 0;
        }
        return Math.max(0, Math.min(SLOTS - 1, newValue));
    }

    @Override
    public String display() {
        return String.valueOf(get() + 1);
    }
}
