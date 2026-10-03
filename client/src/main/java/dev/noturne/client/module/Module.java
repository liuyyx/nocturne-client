package dev.noturne.client.module;

import dev.noturne.client.value.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for every feature module.
 *
 * <p>{@code enabled} is an <em>armed</em> flag: it is what the GUI toggles and what survives a
 * restart. Whether a module actually runs is decided by the registry, which only activates
 * modules while the game is in a state where acting is meaningful.
 */
public abstract class Module {

    private volatile boolean enabled;
    private final List<Value<?>> values = new ArrayList<Value<?>>();

    /** Registers a setting; returns it so fields can be assigned in one expression. */
    protected final <V extends Value<?>> V add(V value) {
        values.add(value);
        return value;
    }

    /** Settings owned by this module, in registration order. */
    public final List<Value<?>> values() {
        return Collections.unmodifiableList(values);
    }

    public abstract String name();

    public abstract Category category();

    public final boolean isEnabled() {
        return enabled;
    }

    public final void setEnabled(boolean value) {
        if (this.enabled == value) {
            return;
        }
        this.enabled = value;
        if (value) {
            onEnable();
        } else {
            onDisable();
        }
    }

    public final void toggle() {
        setEnabled(!enabled);
    }

    /** Called when the module becomes armed. */
    protected void onEnable() {
    }

    /** Called when the module is disarmed. Must undo everything {@link #onEnable()} set up. */
    protected void onDisable() {
    }

    /** Called once per game tick while the module is armed and active. */
    public void onTick() {
    }

    @Override
    public String toString() {
        return name() + "[" + category() + (enabled ? ",on" : ",off") + "]";
    }
}
