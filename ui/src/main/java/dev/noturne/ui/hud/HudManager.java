package dev.noturne.ui.hud;

import dev.noturne.ui.render.Renderer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns the HUD elements, their order, and whether the HUD draws at all. */
public final class HudManager {

    private final List<HudElement> elements = new ArrayList<HudElement>();
    private boolean visible = true;

    public void add(HudElement element) {
        elements.add(element);
    }

    public void remove(HudElement element) {
        elements.remove(element);
    }

    public List<HudElement> elements() {
        return Collections.unmodifiableList(elements);
    }

    public HudElement byId(String id) {
        for (HudElement element : elements) {
            if (element.id().equals(id)) {
                return element;
            }
        }
        return null;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean value) {
        this.visible = value;
    }

    /** Draws every enabled element in registration order. */
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        for (HudElement element : elements) {
            if (element.isEnabled()) {
                element.render(renderer);
            }
        }
    }
}
