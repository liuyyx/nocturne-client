package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.module.ModuleRegistry;
import dev.noturne.ui.RecordingRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickGuiTest {

    static final class TestModule extends Module {
        private final String name;
        private final Category category;

        TestModule(String name, Category category) {
            this.name = name;
            this.category = category;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Category category() {
            return category;
        }
    }

    private static ClickGui guiWith(TestModule... modules) {
        ModuleRegistry registry = new ModuleRegistry();
        for (TestModule module : modules) {
            registry.register(module);
        }
        return new ClickGui(registry);
    }

    @Test
    void closedGuiDrawsNothingAndSwallowsNoInput() {
        ClickGui gui = guiWith(new TestModule("Fly", Category.MOVEMENT));
        RecordingRenderer renderer = new RecordingRenderer();

        gui.render(renderer);
        assertTrue(renderer.calls.isEmpty(), "closed GUI must not draw");

        assertFalse(gui.mouseClicked(20, 20, 0));
    }

    @Test
    void clickingModuleRowTogglesIt() {
        TestModule fly = new TestModule("Fly", Category.MOVEMENT);
        ClickGui gui = guiWith(fly);
        gui.setOpen(true);

        CategoryPanel movement = null;
        for (CategoryPanel panel : gui.panels()) {
            if (panel.category() == Category.MOVEMENT) {
                movement = panel;
            }
        }
        assertTrue(movement != null, "MOVEMENT panel must exist");

        ModuleRow row = movement.rows().get(0);
        assertFalse(fly.isEnabled());
        assertTrue(row.mouseClicked(row.x() + 2, row.y() + 2, 0));
        assertTrue(fly.isEnabled());
    }

    @Test
    void collapseHidesRowsAndShrinksPanel() {
        ClickGui gui = guiWith(new TestModule("Fly", Category.MOVEMENT));
        CategoryPanel panel = null;
        for (CategoryPanel candidate : gui.panels()) {
            if (candidate.category() == Category.MOVEMENT) {
                panel = candidate;
            }
        }
        float fullHeight = panel.height();
        assertTrue(panel.isExpanded());

        assertTrue(panel.mouseClicked(panel.x() + 2, panel.y() + 2, 0), "header click collapses");
        assertFalse(panel.isExpanded());
        assertTrue(panel.height() < fullHeight);
        assertFalse(panel.rows().get(0).isVisible());

        panel.mouseClicked(panel.x() + 2, panel.y() + 2, 0);
        assertTrue(panel.isExpanded());
        assertTrue(panel.rows().get(0).isVisible());
    }

    @Test
    void escapeClosesGui() {
        ClickGui gui = guiWith(new TestModule("Fly", Category.MOVEMENT));
        gui.setOpen(true);
        assertTrue(gui.keyPressed(256, 0));
        assertFalse(gui.isOpen());
    }
}
