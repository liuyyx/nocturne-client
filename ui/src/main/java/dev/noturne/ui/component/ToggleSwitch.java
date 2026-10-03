package dev.noturne.ui.component;

import dev.noturne.ui.anim.Animation;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.function.Consumer;

/**
 * On/off switch whose knob slides between the two extremes.
 */
public class ToggleSwitch extends Component {

    private final Consumer<Boolean> onChange;
    private final Animation slide = new Animation(Theme.EXPAND_MS, Animation.Easing.EASE_OUT_CUBIC, 0f);
    private boolean enabled;

    public ToggleSwitch(boolean enabled, Consumer<Boolean> onChange) {
        this.enabled = enabled;
        this.onChange = onChange;
        slide.set(enabled ? 1f : 0f);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        setEnabled(value, System.currentTimeMillis());
    }

    public void setEnabled(boolean value, long nowMs) {
        if (this.enabled == value) {
            return;
        }
        this.enabled = value;
        slide.animateTo(value ? 1f : 0f, nowMs);
        if (onChange != null) {
            onChange.accept(value);
        }
    }

    public void update(long nowMs) {
        slide.update(nowMs);
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        float t = slide.value();
        float knobSize = height - 4f;
        float travel = width - knobSize - 4f;

        dev.noturne.ui.render.Color track = Theme.DISABLED.mix(Theme.ENABLED, t);
        renderer.roundedRect(x, y, width, height, height / 2f, track);
        renderer.roundedRect(x + 2f + travel * t, y + 2f, knobSize, knobSize, knobSize / 2f, Theme.TEXT);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        setEnabled(!enabled, System.currentTimeMillis());
        return true;
    }
}
