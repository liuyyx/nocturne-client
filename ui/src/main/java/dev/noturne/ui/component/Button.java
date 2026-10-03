package dev.noturne.ui.component;

import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

/** A labelled click target. */
public class Button extends Component {

    private final String label;
    private final Runnable action;

    public Button(String label, Runnable action) {
        this.label = label;
        this.action = action;
    }

    public String label() {
        return label;
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        Renderer r = renderer;
        r.roundedRect(x, y, width, height, Theme.RADIUS_SMALL,
                hovered ? Theme.ACCENT_HOVER : Theme.ACCENT);
        float textX = x + (width - r.textWidth(label, Theme.FONT_SIZE_SMALL)) / 2f;
        float textY = y + (height - r.textHeight(Theme.FONT_SIZE_SMALL)) / 2f;
        r.text(label, textX, textY, Theme.FONT_SIZE_SMALL, Theme.TEXT);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        if (action != null) {
            action.run();
        }
        return true;
    }
}
