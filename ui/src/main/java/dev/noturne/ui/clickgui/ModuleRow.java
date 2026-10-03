package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Module;
import dev.noturne.ui.anim.Animation;
import dev.noturne.ui.component.Component;
import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

/**
 * One clickable module line: name on the left, on/off reflected by colour, hover highlight.
 */
public final class ModuleRow extends Component {

    private final Module module;
    private final Animation highlight =
            new Animation(Theme.HOVER_MS, Animation.Easing.EASE_OUT_CUBIC, 0f);

    public ModuleRow(Module module) {
        this.module = module;
    }

    public Module module() {
        return module;
    }

    public void update(long nowMs) {
        highlight.animateTo(hovered ? 1f : 0f, nowMs);
        highlight.update(nowMs);
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        Color background = Theme.PANEL_HEADER.mix(Theme.PANEL_HEADER_HOVER, highlight.value());
        renderer.roundedRect(x, y, width, height - 1f, Theme.RADIUS_SMALL, background);
        Color textColor = module.isEnabled() ? Theme.ENABLED : Theme.TEXT_DIM;
        renderer.text(module.name(), x + 4f, y + 3f, Theme.FONT_SIZE_SMALL, textColor);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        module.toggle();
        return true;
    }
}
