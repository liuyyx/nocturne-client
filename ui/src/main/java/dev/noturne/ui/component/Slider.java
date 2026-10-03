package dev.noturne.ui.component;

import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.function.Consumer;

/**
 * Horizontal numeric slider. Clicking anywhere on the track jumps the knob there; dragging
 * updates continuously. The value is clamped to {@code [min, max]}.
 */
public class Slider extends Component {

    private final float min;
    private final float max;
    private final Consumer<Float> onChange;
    private float value;
    private boolean dragging;

    public Slider(float min, float max, float value, Consumer<Float> onChange) {
        if (max <= min) {
            throw new IllegalArgumentException("max must be greater than min");
        }
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        this.value = clamp(value);
    }

    public float value() {
        return value;
    }

    public void setValue(float newValue) {
        float clamped = clamp(newValue);
        if (clamped == value) {
            return;
        }
        value = clamped;
        if (onChange != null) {
            onChange.accept(value);
        }
    }

    public boolean isDragging() {
        return dragging;
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        float knobWidth = 6f;
        float fraction = (value - min) / (max - min);
        float trackY = y + height / 2f - 1.5f;

        renderer.rect(x, trackY, width, 3f, Theme.DISABLED);
        renderer.rect(x, trackY, width * fraction, 3f, Theme.ACCENT);
        renderer.rect(x + width * fraction - knobWidth / 2f, y + height / 2f - 4f,
                knobWidth, 8f, Theme.ACCENT_HOVER);

        String label = format(value);
        renderer.text(label, x + width - renderer.textWidth(label, Theme.FONT_SIZE_SMALL),
                y + (height - renderer.textHeight(Theme.FONT_SIZE_SMALL)) / 2f,
                Theme.FONT_SIZE_SMALL, Theme.TEXT_DIM);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        dragging = true;
        applyFromMouse(mx);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!dragging) {
            return false;
        }
        applyFromMouse(mx);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (!dragging) {
            return false;
        }
        dragging = false;
        return true;
    }

    private void applyFromMouse(double mx) {
        float fraction = (float) ((mx - x) / width);
        setValue(min + fraction * (max - min));
    }

    private float clamp(float v) {
        return v < min ? min : (v > max ? max : v);
    }

    /** Trims trailing {@code .0} for whole numbers so the label stays compact. */
    protected String format(float v) {
        if (v == Math.rint(v)) {
            return Integer.toString((int) v);
        }
        return String.format("%.2f", v);
    }
}
