package dev.nocturne.client.value;

/**
 * 模块持有的具名、带类型的设置项。
 *
 * <p>每个值都携带自己的默认值，使得 GUI 可以提供「恢复默认」，配置层也能据此判断哪些项需要持久化。
 * 子类通过覆写 {@link #coerce(Object)} 施加各自的取值约束（钳制、选项校验）。
 *
 * <p>不变量：构造完成时默认值与当前值都已经是 {@code coerce} 的固定点，因此 {@link #reset()}、
 * {@link #isDefault()} 与所有写入路径使用同一套约束，不会把未经归一化的原始入参写回当前值。
 */
public abstract class Value<T> {

    /** 设置项名称，通常直接作为 GUI 上的显示标签。 */
    private final String name;
    /**
     * 构造时确定的默认值，可能为 {@code null}；由子类在构造末尾通过 {@link #normaliseDefault()}
     * 归一化后不再变化。子类若不调用该方法，默认值保持原始入参。
     */
    private T defaultValue;
    /**
     * 当前值；由 {@link #set(Object)} 经 {@code coerce} 归一化后写入。GUI 线程写入、渲染/游戏线程读取，
     * 故声明为 {@code volatile}，与 {@link dev.nocturne.client.module.Module#isEnabled()} 的跨线程约定一致。
     */
    private volatile T value;

    /**
     * 构造一个值，初始状态即为默认值。
     *
     * @param name         设置项名称，不可为 {@code null}
     * @param defaultValue 默认值原始入参；{@link #coerce(Object)} 尚未可用（子类约束字段还未初始化），
     *                     故此处不做归一化，由子类构造末尾调用 {@link #normaliseDefault()} 完成
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

    /** @return 当前值；构造完成后不会为 {@code null}（构造期即为默认值或归一化结果） */
    public T get() {
        return value;
    }

    /** 写入新值，写入前经 {@link #coerce(Object)} 归一化，因此调用方无需自行做范围检查。 */
    public void set(T newValue) {
        this.value = coerce(newValue);
    }

    /** @return 构造时记录并经 {@link #normaliseDefault()} 归一化后的默认值 */
    public T defaultValue() {
        return defaultValue;
    }

    /**
     * 恢复为默认值。
     *
     * <p>仍经 {@link #set(Object)} 走一遍 {@code coerce}：默认值本身已是 {@code coerce} 的固定点，
     * 该调用不会改变结果，但可保证 {@code reset()} 与其它写入路径共享同一条归一化通道。
     */
    public void reset() {
        set(defaultValue);
    }

    /**
     * 用 {@link #coerce(Object)} 归一化并记录默认值。
     *
     * <p>调用时机：必须在本子类的取值约束字段（如 {@code min}/{@code max}/{@code options}）
     * 初始化完成<b>之后</b>、构造函数返回之前调用；否则 {@code coerce} 会读到尚未初始化的约束。
     * 归一化后 {@link #reset()} 写回的默认值必然落在合法取值域内。
     */
    protected final void normaliseDefault() {
        this.defaultValue = coerce(this.defaultValue);
    }

    /** @return 当前值是否等于默认值；配置层据此决定是否需要写出该设置项 */
    public boolean isDefault() {
        return defaultValue == null ? value == null : defaultValue.equals(value);
    }

    /**
     * 归一化传入的值（钳制、选项查找）；默认实现为恒等变换，即不做任何校验。
     *
     * <p>实现必须是幂等的，且不得返回 {@code null}（除非该类确实允许 null 值），因为返回值会直接
     * 成为当前值。
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
