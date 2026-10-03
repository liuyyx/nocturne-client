package dev.noturne.ui.hud;

import dev.noturne.ui.render.Renderer;

/**
 * A single on-screen readout (FPS, coordinates, key state …).
 *
 * <p>Elements are positioned in screen pixels by their anchor point and draw themselves; the
 * manager owns ordering and enablement.
 */
public abstract class HudElement {

    private final String id;
    private boolean enabled = true;
    protected float x;
    protected float y;

    protected HudElement(String id) {
        this.id = id;
    }

    public final String id() {
        return id;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    public final void setEnabled(boolean value) {
        this.enabled = value;
    }

    public final float x() {
        return x;
    }

    public final float y() {
        return y;
    }

    public final void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public abstract void render(Renderer renderer);
}
