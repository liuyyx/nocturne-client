package dev.noturne.ui.component;

import dev.noturne.ui.anim.Animation;
import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.function.Consumer;

/**
 * MD3 风格的开关：滑块在轨道两端之间滑动，切换时尺寸与位置同步过渡
 *（关 8px / 开 12px，内缩关 4 / 开 2，规格见 {@link Theme}）。
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

        // 轨道由 SURFACE_CONTAINER_HIGHEST 过渡到 PRIMARY，滑块由 OUTLINE 过渡到 ON_PRIMARY
        Color track = Theme.SURFACE_CONTAINER_HIGHEST.mix(Theme.PRIMARY, t);
        Color knobColor = Theme.OUTLINE.mix(Theme.ON_PRIMARY, t);

        // 滑块尺寸 8→12、左缘从「内缩 4」移动到「右端内缩 2」
        float knobSize = Theme.SWITCH_HANDLE_OFF
                + (Theme.SWITCH_HANDLE_ON - Theme.SWITCH_HANDLE_OFF) * t;
        float left = Theme.SWITCH_INSET_OFF
                + (width - Theme.SWITCH_INSET_ON - Theme.SWITCH_HANDLE_ON - Theme.SWITCH_INSET_OFF) * t;

        renderer.roundedRect(x, y, width, height, height / 2f, track);
        renderer.roundedRect(x + left, y + (height - knobSize) / 2f, knobSize, knobSize,
                knobSize / 2f, knobColor);
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
