package dev.nocturne.client.value;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 固定选项集合上的多选设置；值是不可变、按声明顺序排列的集合。 */
public final class MultiBoolValue extends Value<Set<String>> {

    private final List<String> options;

    public MultiBoolValue(String name, Collection<String> options, Collection<String> defaultSelected) {
        super(name, defaultSelected == null ? Collections.<String>emptySet()
                : new LinkedHashSet<String>(defaultSelected));
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("options must not be empty");
        }
        Set<String> unique = new LinkedHashSet<String>();
        for (String option : options) {
            if (option == null || option.isEmpty() || !unique.add(option)) {
                throw new IllegalArgumentException("invalid option in " + name);
            }
        }
        this.options = Collections.unmodifiableList(new ArrayList<String>(unique));
        set(defaultValue());
        normaliseDefault();
    }

    public List<String> options() {
        return options;
    }

    public boolean selected(String option) {
        return get().contains(option);
    }

    public void toggle(String option) {
        if (!options.contains(option)) {
            return;
        }
        Set<String> next = new LinkedHashSet<String>(get());
        if (!next.add(option)) {
            next.remove(option);
        }
        set(next);
    }

    @Override
    protected Set<String> coerce(Set<String> newValue) {
        Set<String> selected = new LinkedHashSet<String>();
        if (newValue != null) {
            for (String option : options) {
                if (newValue.contains(option)) {
                    selected.add(option);
                }
            }
        }
        return Collections.unmodifiableSet(selected);
    }

    @Override
    public String display() {
        if (get().isEmpty()) {
            return "None";
        }
        StringBuilder out = new StringBuilder();
        for (String option : options) {
            if (!selected(option)) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(option);
        }
        return out.toString();
    }
}
