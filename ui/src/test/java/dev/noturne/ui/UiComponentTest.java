package dev.noturne.ui;

import dev.noturne.ui.anim.Animation;
import dev.noturne.ui.component.Button;
import dev.noturne.ui.component.Component;
import dev.noturne.ui.component.Panel;
import dev.noturne.ui.component.Slider;
import dev.noturne.ui.component.ToggleSwitch;
import dev.noturne.ui.render.Color;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiComponentTest {

    static final class Fixed extends Component {
        int clicks;
        Fixed(float x, float y, float w, float h) {
            setBounds(x, y, w, h);
        }
        @Override public void render(dev.noturne.ui.render.Renderer renderer) { }
        @Override public boolean mouseClicked(double mx, double my, int button) {
            clicks++;
            return true;
        }
    }

    @Test
    void panelRoutesClickToTopmostChild() {
        Panel panel = new Panel();
        panel.setBounds(0, 0, 400, 300);
        Fixed bottom = new Fixed(10, 10, 100, 50);
        Fixed top = new Fixed(10, 10, 100, 50);   // fully overlaps
        panel.add(bottom);
        panel.add(top);

        assertTrue(panel.mouseClicked(20, 20, 0));
        assertEquals(0, bottom.clicks, "covered child must not receive the click");
        assertEquals(1, top.clicks);
    }

    @Test
    void sliderDragUpdatesAndClampsValue() {
        final AtomicReference<Float> reported = new AtomicReference<Float>(Float.NaN);
        Slider slider = new Slider(0f, 100f, 0f, reported::set);
        slider.setBounds(0f, 0f, 100f, 20f);

        slider.mouseClicked(50, 10, 0);
        assertEquals(50f, slider.value(), 0.5f);
        assertEquals(50f, reported.get(), 0.5f);

        slider.mouseDragged(200, 10, 0, 0, 0);   // beyond the right edge
        assertEquals(100f, slider.value(), 0.001f);

        slider.mouseDragged(-50, 10, 0, 0, 0);   // beyond the left edge
        assertEquals(0f, slider.value(), 0.001f);

        assertTrue(slider.mouseReleased(0, 10, 0));
        assertFalse(slider.mouseDragged(50, 10, 0, 0, 0), "drag must end after release");
    }

    @Test
    void buttonRunsActionOnlyWhenHit() {
        AtomicBoolean fired = new AtomicBoolean(false);
        Button button = new Button("Apply", () -> fired.set(true));
        button.setBounds(10, 10, 60, 20);

        assertFalse(button.mouseClicked(200, 200, 0));
        assertFalse(fired.get());

        assertTrue(button.mouseClicked(20, 20, 0));
        assertTrue(fired.get());
    }

    @Test
    void toggleSwitchReportsChangesAndAnimates() {
        AtomicBoolean state = new AtomicBoolean(false);
        ToggleSwitch toggle = new ToggleSwitch(false, state::set);
        toggle.setBounds(0, 0, 20, 10);

        toggle.mouseClicked(5, 5, 0);
        assertTrue(toggle.isEnabled());
        assertTrue(state.get());

        // animation settles on the target value
        toggle.update(0L);
        toggle.update(10_000L);
        RecordingRenderer renderer = new RecordingRenderer();
        toggle.render(renderer);
        assertEquals(2, renderer.count("roundedRect"));
    }

    @Test
    void animationReachesTarget() {
        Animation animation = new Animation(100L, Animation.Easing.LINEAR, 0f);
        animation.animateTo(1f, 0L);
        assertEquals(0.5f, animation.update(50L), 0.001f);
        assertEquals(1f, animation.update(100L), 0.001f);
        assertFalse(animation.isRunning());
    }

    @Test
    void colorMixesAndParsesHex() {
        assertEquals(Color.hex("FF0000"), Color.rgb(255, 0, 0));
        assertEquals(Color.hex("80FF0000").a(), 128);
        assertEquals(Color.rgb(0, 0, 0), Color.BLACK.mix(Color.WHITE, 0f));
        assertEquals(Color.rgb(255, 255, 255), Color.BLACK.mix(Color.WHITE, 1f));
        assertEquals(128, Color.BLACK.mix(Color.WHITE, 0.5f).r(), 1);
    }
}
