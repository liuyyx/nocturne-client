package dev.noturne.client.value;

import java.util.Arrays;
import java.util.List;

/** Enumeration setting: one of a fixed list of named options. */
public final class ModeValue extends Value<String> {

    private final List<String> options;

    public ModeValue(String name, String defaultValue, String... options) {
        super(name, defaultValue);
        if (options == null || options.length == 0) {
            throw new IllegalArgumentException("a mode value needs at least one option");
        }
        this.options = Arrays.asList(options.clone());
        set(defaultValue);
    }

    public List<String> options() {
        return options;
    }

    public int index() {
        int index = options.indexOf(get());
        return index < 0 ? 0 : index;
    }

    public boolean is(String option) {
        return get().equals(option);
    }

    public void next() {
        int next = (index() + 1) % options.size();
        set(options.get(next));
    }

    @Override
    protected String coerce(String newValue) {
        if (newValue == null || !options.contains(newValue)) {
            return options.get(0);
        }
        return newValue;
    }

    @Override
    public String display() {
        return get();
    }
}
