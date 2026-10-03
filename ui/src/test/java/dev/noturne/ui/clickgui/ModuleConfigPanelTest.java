package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.value.BooleanValue;
import dev.noturne.client.value.ModeValue;
import dev.noturne.client.value.NumberValue;
import dev.noturne.ui.RecordingRenderer;
import dev.noturne.ui.component.Component;
import dev.noturne.ui.component.ModeSelector;
import dev.noturne.ui.component.Slider;
import dev.noturne.ui.component.ToggleSwitch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleConfigPanelTest {

    static final class Configured extends Module {
        final BooleanValue enabledValue = add(new BooleanValue("Extra", false));
        final NumberValue speed = add(new NumberValue("Speed", 5.0, 1.0, 10.0, 1.0));
        final ModeValue mode = add(new ModeValue("Mode", "A", "A", "B"));

        @Override
        public String name() {
            return "Configured";
        }

        @Override
        public Category category() {
            return Category.MISC;
        }
    }

    @Test
    void buildsOneEditorPerValueType() {
        ModuleConfigPanel panel = new ModuleConfigPanel();
        assertFalse(panel.isVisible());

        panel.show(new Configured(), 10f, 20f);
        assertTrue(panel.isVisible());
        assertEquals(3, panel.editors().size());

        int toggles = 0;
        int sliders = 0;
        int selectors = 0;
        for (Component editor : panel.editors()) {
            if (editor instanceof ToggleSwitch) {
                toggles++;
            } else if (editor instanceof Slider) {
                sliders++;
            } else if (editor instanceof ModeSelector) {
                selectors++;
            }
        }
        assertEquals(1, toggles);
        assertEquals(1, sliders);
        assertEquals(1, selectors);
        assertTrue(panel.height() > 0f);
    }

    @Test
    void editingControlsWritesBackToTheValue() {
        ModuleConfigPanel panel = new ModuleConfigPanel();
        Configured module = new Configured();
        panel.show(module, 0f, 0f);

        for (Component editor : panel.editors()) {
            if (editor instanceof ToggleSwitch) {
                editor.mouseClicked(editor.x() + 1, editor.y() + 1, 0);
            } else if (editor instanceof Slider) {
                editor.mouseClicked(editor.x() + editor.width() - 1, editor.y() + 1, 0);
            } else if (editor instanceof ModeSelector) {
                editor.mouseClicked(editor.x() + 1, editor.y() + 1, 0);
            }
        }

        assertTrue(module.enabledValue.get(), "toggle writes back to the BooleanValue");
        assertEquals(10.0, module.speed.get(), 1e-9, "slider clamps into range");
        assertEquals("B", module.mode.get(), "mode selector cycles");
    }

    @Test
    void missingModuleStillRendersAHeader() {
        ModuleConfigPanel panel = new ModuleConfigPanel();
        panel.show(null, 0f, 0f);
        RecordingRenderer renderer = new RecordingRenderer();
        panel.render(renderer);
        assertNotNull(renderer);
        assertTrue(renderer.count("roundedRect") >= 2, "panel + header");
    }
}
