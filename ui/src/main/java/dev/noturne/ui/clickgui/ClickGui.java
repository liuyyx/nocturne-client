package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.ModuleRegistry;
import dev.noturne.ui.component.Panel;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * The click GUI: one column per {@link Category}, laid out left to right.
 *
 * <p>Closed by default; {@code Right Shift} opens it, {@code Esc} closes it. While closed it
 * neither draws nor consumes input.
 */
public final class ClickGui extends Panel {

    /** GLFW key code for Escape. */
    private static final int KEY_ESCAPE = 256;

    private final ModuleRegistry registry;
    private final List<CategoryPanel> panels = new ArrayList<CategoryPanel>();
    private boolean open;

    public ClickGui(ModuleRegistry registry) {
        this.registry = registry;

        float cursorX = 12f;
        float top = 12f;
        for (Category category : Category.values()) {
            CategoryPanel panel =
                    new CategoryPanel(category, registry.byCategory(category), cursorX, top);
            panels.add(panel);
            add(panel);
            cursorX += Theme.PANEL_WIDTH + Theme.GAP;
        }
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean value) {
        this.open = value;
    }

    public void toggle() {
        open = !open;
    }

    public List<CategoryPanel> panels() {
        return panels;
    }

    public ModuleRegistry registry() {
        return registry;
    }

    /** Advances animations and hover state. Call once per frame. */
    public void update(long nowMs, double mouseX, double mouseY) {
        if (!open) {
            return;
        }
        for (CategoryPanel panel : panels) {
            panel.update(nowMs);
            panel.updateHeaderHover(mouseX, mouseY);
            for (ModuleRow row : panel.rows()) {
                row.updateHover(mouseX, mouseY);
            }
        }
    }

    @Override
    public void render(Renderer renderer) {
        if (!open) {
            return;
        }
        for (CategoryPanel panel : panels) {
            panel.render(renderer);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!open) {
            return false;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return open && super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return open && super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        if (!open) {
            return false;
        }
        if (keyCode == KEY_ESCAPE) {
            open = false;
            return true;
        }
        return super.keyPressed(keyCode, modifiers);
    }
}
