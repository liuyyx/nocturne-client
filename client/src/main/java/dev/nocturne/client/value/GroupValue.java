package dev.nocturne.client.value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一组可展开/折叠的子设置；布尔值只控制 GUI 展开状态，不参与模块开关。 */
public final class GroupValue extends Value<Boolean> {

    private final List<Value<?>> children;

    public GroupValue(String name, List<Value<?>> children, boolean defaultOpen) {
        super(name, defaultOpen);
        if (children == null) {
            throw new IllegalArgumentException("children == null");
        }
        List<Value<?>> copy = new ArrayList<Value<?>>(children.size());
        for (Value<?> child : children) {
            if (child == null) {
                throw new IllegalArgumentException("null child in " + name);
            }
            copy.add(child);
        }
        this.children = Collections.unmodifiableList(copy);
        set(defaultValue());
        normaliseDefault();
    }

    public List<Value<?>> children() {
        return children;
    }

    @Override
    protected Boolean coerce(Boolean newValue) {
        return newValue != null && newValue;
    }

    @Override
    public String display() {
        return get() ? "Expanded" : "Collapsed";
    }
}
