package dev.noturne.client.value;

import java.util.Arrays;
import java.util.List;

/** 枚举型设置：从一组固定的可选项中取值，当前值必须属于该选项列表。 */
public final class ModeValue extends Value<String> {

    /** 可选值列表；构造时克隆入参，外部对原数组的修改不会影响本实例。 */
    private final List<String> options;

    /**
     * 构造型选择设置，并立即用 {@code coerce} 校验默认值。
     *
     * @throws IllegalArgumentException 当选项列表为空——否则没有任何合法取值
     */
    public ModeValue(String name, String defaultValue, String... options) {
        super(name, defaultValue);
        if (options == null || options.length == 0) {
            throw new IllegalArgumentException("a mode value needs at least one option");
        }
        this.options = Arrays.asList(options.clone());
        set(defaultValue);
    }

    /** @return 不可变的可选项列表，顺序即 GUI 中的展示顺序 */
    public List<String> options() {
        return options;
    }

    /** @return 当前值在选项列表中的下标；当前值不在列表中时回退为 0 */
    public int index() {
        int index = options.indexOf(get());
        return index < 0 ? 0 : index;
    }

    /** @return 当前值是否等于给定选项；{@code null} 或不匹配均返回 false */
    public boolean is(String option) {
        return get().equals(option);
    }

    /** 循环切换到下一个选项（末项回到首项）；仅供 GUI 绑定调用。 */
    public void next() {
        int next = (index() + 1) % options.size();
        set(options.get(next));
    }

    /**
     * 校验传入值是否为合法选项。
     *
     * @param newValue 原始值；{@code null} 或非法选项一律回退为首个选项
     * @return 合法选项本身
     */
    @Override
    protected String coerce(String newValue) {
        if (newValue == null || !options.contains(newValue)) {
            return options.get(0);
        }
        return newValue;
    }

    /** @return 当前选项名，可直接用作 GUI 文本 */
    @Override
    public String display() {
        return get();
    }
}
