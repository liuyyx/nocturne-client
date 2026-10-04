package dev.noturne.client.value;

/**
 * 模块持有的具名、带类型的设置项。
 *
 * <p>每个值都携带自己的默认值，使得 GUI 可以提供「恢复默认」，配置层也能据此判断哪些项需要持久化。
 * 子类通过覆写 {@link #coerce(Object)} 施加各自的取值约束（钳制、选项校验）。
 */
public abstract class Value<T> {

    /** 设置项名称，通常直接作为 GUI 上的显示标签。 */
    private final String name;
    /** 构造时确定的默认值，不可变；可能为 {@code null}。 */
    private final T defaultValue;
    /** 当前值；由 {@link #set(Object)} 经 {@code coerce} 归一化后写入，无并发保护。 */
    private T value;

    /**
     * 构造一个值，初始状态即为默认值。
     *
     * @param name         设置项名称，不可为 {@code null}
     * @param defaultValue 默认值；不做归一化，直接作为初始当前值
     */
    protected Value(String name, T defaultValue) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    /** @return 设置项名称 */
    public String name() {
        return name;
    }

    /** @return 当前值，可能为 {@code null}（若默认值为 null 且未被显式设置） */
    public T get() {
        return value;
    }

    /** 写入新值，写入前经 {@link #coerce(Object)} 归一化，因此调用方无需自行做范围检查。 */
    public void set(T newValue) {
        this.value = coerce(newValue);
    }

    /** @return 构造时记录的默认值 */
    public T defaultValue() {
        return defaultValue;
    }

    /** 恢复为默认值；绕过 {@code coerce}，因为默认值本身即视为合法值。 */
    public void reset() {
        this.value = defaultValue;
    }

    /** @return 当前值是否等于默认值；配置层据此决定是否需要写出该设置项 */
    public boolean isDefault() {
        return defaultValue == null ? value == null : defaultValue.equals(value);
    }

    /**
     * 归一化传入的值（钳制、选项查找）；默认实现为恒等变换，即不做任何校验。
     *
     * @param newValue 调用方传入的原始值，可能为 {@code null}
     * @return 归一化后的值
     */
    protected T coerce(T newValue) {
        return newValue;
    }

    /** GUI 使用的简短可读文本，例如 {@code "12.5"} 或 {@code "Toggle"}；不得返回 {@code null}。 */
    public abstract String display();

    @Override
    public String toString() {
        return name + "=" + display();
    }
}
