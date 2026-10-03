package dev.noturne.client.hud;

import java.util.function.Supplier;

/**
 * Seam between the client and whatever renders HUD lines.
 *
 * <p>The client must not depend on the UI module, so modules publish text lines here and the UI
 * module supplies an implementation backed by {@code HudManager}.
 */
public interface HudSink {

    /** Registers (or replaces) a line whose text is pulled on every frame. */
    void add(String id, Supplier<String> text);

    void remove(String id);

    boolean has(String id);
}
