package dev.nocturne.client.value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 枚举型设置：从一组固定的可选项中取值，当前值必须属于该选项列表。 */
public final class ModeValue extends Value<String> {

    /** 可选值列表；构造时校验并复制入参，对外只读，外部无法借此修改本实例。 */
    private final List<String> options;

    /**
     * 构造型选择设置，并立即用 {@code coerce} 校验默认值。
     *
     * @throws IllegalArgumentException 选项列表为空、含 {@code null} 元素或含重复项时抛出
     */
    public ModeValue(String name, String defaultValue, String... options) {
        super(name, defaultValue);
        if (options == null || options.length == 0) {
            throw new IllegalArgumentException("a mode value needs at least one option");
        }
        // 先校验再复制：null 元素会让 is()/display() 直接 NPE；重复元素会让 next() 永远跳过后者，
        // 使部分选项不可达。两者都在构造期拒绝，而不是留到运行期静默出错。
        Set<String> unique = new LinkedHashSet<String>();
        for (String option : options) {
            if (option == null) {
                throw new IllegalArgumentException("mode option must not be null");
            }
            if (!unique.add(option)) {
                throw new IllegalArgumentException("duplicate mode option: " + option);
            }
        }
        // 双重包装：new ArrayList 提供可变副本，unmodifiableList 阻止外部 set，与「只读列表」契约一致
        this.options = Collections.unmodifiableList(new ArrayList<String>(unique));
        normaliseDefault();
        set(defaultValue());
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

    /** 循环切换到下一个选项（末项回到首项）；选项列表经构造期去重，故每次调用都必然前进 */
    public void next() {
        int next = (index() + 1) % options.size();
        set(options.get(next));
    }

    /**
     * 校验传入值是否为合法选项。
     *
     * @param newValue 原始值；{@code null} 或非法选项一律回退为首个选项
     * @return 合法选项本身，永不为 {@code null}
     */
    @Override
    protected String coerce(String newValue) {
        if (newValue == null || !options.contains(newValue)) {
            return options.get(0);
        }
        return newValue;
    }

    /** @return 当前选项名，可直接用作 GUI 文本；因 {@code coerce} 保证合法，永不为 {@code null} */
    @Override
    public String display() {
        return get();
    }
}
