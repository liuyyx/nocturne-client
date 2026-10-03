package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Module;
import dev.noturne.client.value.BooleanValue;
import dev.noturne.client.value.ModeValue;
import dev.noturne.client.value.NumberValue;
import dev.noturne.client.value.Value;
import dev.noturne.ui.component.Component;
import dev.noturne.ui.component.ModeSelector;
import dev.noturne.ui.component.Panel;
import dev.noturne.ui.component.Slider;
import dev.noturne.ui.component.ToggleSwitch;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Floating editor for one module's settings, opened from the click GUI.
 *
 * <p>Builds one control per {@link Value}, choosing the widget by value type.
 */
public final class ModuleConfigPanel extends Panel {

    private static final float ROW = 20f;

    private Module module;
    private final List<Component> editors = new ArrayList<Component>();

    public ModuleConfigPanel() {
        setVisible(false);
    }

    public Module module() {
        return module;
    }

    public List<Component> editors() {
        return Collections.unmodifiableList(editors);
    }

    /** Rebuilds the editor for {@code module} at the given screen position and shows it. */
    public void show(Module module, float x, float y) {
        this.module = module;
        children.clear();
        editors.clear();

        float width = 178f;
        float cursor = Theme.HEADER_HEIGHT + Theme.GAP;

        if (module != null) {
            for (Value<?> value : module.values()) {
                Component editor = create(value, x + Theme.GAP, y + cursor, width - Theme.GAP * 2f);
                if (editor == null) {
                    continue;
                }
                editors.add(editor);
                add(editor);
                cursor += ROW;
            }
        }
        if (editors.isEmpty()) {
            cursor += ROW;
        }
        setBounds(x, y, width, cursor + Theme.GAP);
        setVisible(true);
    }

    public void hide() {
        setVisible(false);
    }

    public void update(long nowMs, double mouseX, double mouseY) {
        for (Component editor : editors) {
            editor.updateHover(mouseX, mouseY);
        }
    }

    private static Component create(Value<?> value, float x, float y, float width) {
        if (value instanceof BooleanValue) {
            final BooleanValue bool = (BooleanValue) value;
            ToggleSwitch toggle = new ToggleSwitch(bool.get(), v -> bool.set(v));
            toggle.setBounds(x, y + 3f, 24f, 13f);
            return toggle;
        }
        if (value instanceof NumberValue) {
            final NumberValue number = (NumberValue) value;
            Slider slider = new Slider((float) number.min(), (float) number.max(),
                    number.get().floatValue(), f -> number.set(f.doubleValue()));
            slider.setBounds(x, y + 2f, width, 15f);
            return slider;
        }
        if (value instanceof ModeValue) {
            ModeSelector selector = new ModeSelector((ModeValue) value);
            selector.setBounds(x, y + 2f, width, 15f);
            return selector;
        }
        return null;
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        renderer.roundedRect(x, y, width, height, Theme.RADIUS, Theme.PANEL);
        renderer.roundedRect(x, y, width, Theme.HEADER_HEIGHT, Theme.RADIUS, Theme.PANEL_HEADER);
        String title = module == null ? "settings" : module.name();
        renderer.text(title, x + Theme.PADDING, y + 6f, Theme.FONT_SIZE, Theme.TEXT);

        if (module != null && module.values().isEmpty()) {
            renderer.text("no settings", x + Theme.GAP, y + Theme.HEADER_HEIGHT + Theme.GAP + 4f,
                    Theme.FONT_SIZE_SMALL, Theme.TEXT_MUTED);
        }
        super.render(renderer);
    }
}
