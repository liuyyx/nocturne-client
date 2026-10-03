package dev.noturne.ui.hud;

import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.function.Supplier;

/**
 * Text readout whose content is pulled from a supplier each frame, so it always shows live state
 * without the caller having to push updates.
 */
public final class TextElement extends HudElement {

    private final Supplier<String> text;
    private final Color color;
    private final float size;
    private final boolean shadow;

    public TextElement(String id, Supplier<String> text, float size, Color color) {
        this(id, text, size, color, true);
    }

    public TextElement(String id, Supplier<String> text, float size, Color color, boolean shadow) {
        super(id);
        this.text = text;
        this.size = size;
        this.color = color == null ? Theme.TEXT : color;
        this.shadow = shadow;
    }

    public String currentText() {
        String value = text == null ? null : text.get();
        return value == null ? "" : value;
    }

    @Override
    public void render(Renderer renderer) {
        String value = currentText();
        if (value.isEmpty()) {
            return;
        }
        if (shadow) {
            renderer.text(value, x + 1f, y + 1f, size, Theme.SHADOW);
        }
        renderer.text(value, x, y, size, color);
    }
}
