package dev.nocturne.client.value;

/** 开/关设置。 {@code null} 会被归一化为 {@code false}，因此调用 {@link #get()} 永不返回 null。 */
public final class BooleanValue extends Value<Boolean> {

    /**
     * 构造布尔设置。
     *
     * @param name         显示名称
     * @param defaultValue 初始状态
     */
    public BooleanValue(String name, boolean defaultValue) {
        super(name, defaultValue);
    }

    /** 取反当前值；GUI 上的开关点击即调用此方法。 */
    public void toggle() {
        set(!get());
    }

    /** 把 {@code null} 视作 {@code false}，避免调用方到处做空值判断。 */
    @Override
    protected Boolean coerce(Boolean newValue) {
        return newValue == null ? Boolean.FALSE : newValue;
    }

    /** @return {@code "On"} 或 {@code "Off"} */
    @Override
    public String display() {
        return get() ? "On" : "Off";
    }
}
