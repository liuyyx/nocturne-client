package dev.noturne.ui.component;

import dev.noturne.client.value.ModeValue;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

/**
 * Cycles through a {@link ModeValue}'s options on click and shows the current one.
 */
public final class ModeSelector extends Component {

    private final ModeValue value;

    public ModeSelector(ModeValue value) {
        this.value = value;
    }

    public ModeValue value() {
        return value;
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        renderer.roundedRect(x, y, width, height, Theme.RADIUS_SMALL,
                hovered ? Theme.PANEL_HEADER_HOVER : Theme.PANEL_HEADER);

        String name = value.name();
        renderer.text(name, x + 5f, y + 3f, Theme.FONT_SIZE_SMALL, Theme.TEXT_MUTED);

        String current = value.display();
        float currentWidth = renderer.textWidth(current, Theme.FONT_SIZE_SMALL);
        renderer.text(current, x + width - currentWidth - 5f, y + 3f, Theme.FONT_SIZE_SMALL, Theme.ACCENT);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        value.next();
        return true;
    }
}
