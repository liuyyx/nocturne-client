package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.ui.anim.Animation;
import dev.noturne.ui.component.Panel;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One draggable-looking category column: a header that collapses the panel, plus a row per module.
 */
public final class CategoryPanel extends Panel {

    private final Category category;
    private final List<ModuleRow> rows = new ArrayList<ModuleRow>();
    private final Animation expand =
            new Animation(Theme.EXPAND_MS, Animation.Easing.EASE_OUT_CUBIC, 1f);
    private boolean expanded = true;

    public CategoryPanel(Category category, List<Module> modules, float x, float y) {
        this.category = category;
        setBounds(x, y, Theme.PANEL_WIDTH, 0f);

        float cursor = Theme.HEADER_HEIGHT + Theme.GAP;
        for (Module module : modules) {
            ModuleRow row = new ModuleRow(module);
            row.setBounds(x + Theme.GAP, y + cursor, Theme.PANEL_WIDTH - Theme.GAP * 2f, Theme.ROW_HEIGHT);
            rows.add(row);
            add(row);
            cursor += Theme.ROW_HEIGHT;
        }
        height = rows.isEmpty() ? Theme.HEADER_HEIGHT : cursor;
    }

    public Category category() {
        return category;
    }

    public List<ModuleRow> rows() {
        return Collections.unmodifiableList(rows);
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean value) {
        if (this.expanded == value) {
            return;
        }
        this.expanded = value;
        for (ModuleRow row : rows) {
            row.setVisible(value);
        }
        float collapsed = Theme.HEADER_HEIGHT;
        float full = collapsed + Theme.GAP + rows.size() * Theme.ROW_HEIGHT;
        height = value ? full : collapsed;
    }

    public void update(long nowMs) {
        expand.update(nowMs);
        for (ModuleRow row : rows) {
            row.update(nowMs);
        }
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        renderer.roundedRect(x, y, width, height, Theme.RADIUS, Theme.PANEL);

        float headerColor = hoveredHeader ? 1f : 0f;
        renderer.roundedRect(x, y, width, Theme.HEADER_HEIGHT, Theme.RADIUS,
                Theme.PANEL_HEADER.mix(Theme.PANEL_HEADER_HOVER, headerColor));
        renderer.text(category.name(), x + Theme.PADDING, y + 6f, Theme.FONT_SIZE, Theme.TEXT);
        renderer.text(expanded ? "-" : "+",
                x + width - Theme.PADDING - renderer.textWidth(expanded ? "-" : "+", Theme.FONT_SIZE),
                y + 6f, Theme.FONT_SIZE, Theme.TEXT_MUTED);

        super.render(renderer);
    }

    private boolean hoveredHeader;

    /** Header click collapses/expands; anything else falls through to the rows. */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && mx >= x && mx <= x + width && my >= y && my <= y + Theme.HEADER_HEIGHT) {
            setExpanded(!expanded);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    /** Kept separate from {@link #hovered} so the header highlight has its own state. */
    public void updateHeaderHover(double mx, double my) {
        hoveredHeader = visible && mx >= x && mx <= x + width && my >= y && my <= y + Theme.HEADER_HEIGHT;
    }
}
