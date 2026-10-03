package dev.noturne.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds every registered {@link Module} and drives their tick/activation lifecycle.
 *
 * <p>Activation gate: a module only runs while {@code active} is true. The caller flips that
 * flag on the transitions into and out of "in a world with a living player", so modules stand
 * down at the main menu and on the respawn screen instead of acting where it would be obvious.
 */
public final class ModuleRegistry {

    private final Map<String, Module> modules = new LinkedHashMap<String, Module>();
    private volatile boolean active;

    public synchronized void register(Module module) {
        String name = module.name();
        if (modules.containsKey(name)) {
            throw new IllegalArgumentException("duplicate module name: " + name);
        }
        modules.put(name, module);
    }

    public synchronized Module get(String name) {
        return modules.get(name);
    }

    public synchronized List<Module> all() {
        return Collections.unmodifiableList(new ArrayList<Module>(modules.values()));
    }

    public synchronized List<Module> byCategory(Category category) {
        List<Module> out = new ArrayList<Module>();
        for (Module module : modules.values()) {
            if (module.category() == category) {
                out.add(module);
            }
        }
        return out;
    }

    public synchronized int size() {
        return modules.size();
    }

    /** Transitions into/out of the state where armed modules are allowed to run. */
    public synchronized void setActive(boolean value) {
        if (this.active == value) {
            return;
        }
        this.active = value;
    }

    public boolean isActive() {
        return active;
    }

    /** Ticks every armed module; no-op while inactive. */
    public void tick() {
        if (!active) {
            return;
        }
        for (Module module : all()) {
            if (module.isEnabled()) {
                module.onTick();
            }
        }
    }
}
