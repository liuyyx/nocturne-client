package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.ui.component.Panel;
import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个分类栏：可折叠的标题栏 + 每个模块一行。
 *
 * <p>视觉与布局逐值对齐 Epsilon 的 dropdown 面板：面板 130 宽、圆角 10、标题栏 28 高，
 * 模块行 19 高、紧贴排列（行自身绘制 0.5px 分隔线），底部留 8px 空白。
 *
 * <p>标题栏同时是拖动把手——按住并移动即整列跟随指针，原地按下再松开则折叠/展开。两者用位移阈值
 * 区分，因此「想拖动却触发了折叠」不会发生。折叠只隐藏内容并把高度收为标题栏（宽度不变），
 * 与参照实现一致。
 *
 * <p>行使用绝对坐标布局（继承自 {@link Panel} 的约定），所以移动整列时必须同步平移所有行。
 */
public final class CategoryPanel extends Panel {

    /** 判定为拖动而非点击的位移阈值（像素）：容忍手抖，又不至于让拖动显得迟钝。 */
    private static final double DRAG_THRESHOLD = 3d;

    /** 本栏对应的模块分类。 */
    private final Category category;
    /** 本栏的模块行，顺序即绘制顺序。 */
    private final List<ModuleRow> rows = new ArrayList<ModuleRow>();
    private boolean expanded = true;

    /** 指针是否按在标题栏上（尚未判定是点击还是拖动）。 */
    private boolean pressed;
    /** 是否已越过阈值、进入拖动状态。 */
    private boolean dragging;
    /** 按下时的指针坐标，作为拖动位移的基准。 */
    private double pressX;
    private double pressY;
    /** 按下时面板左上角坐标；拖动时按「基准 + 位移」计算新位置，避免逐帧累加产生漂移。 */
    private float originX;
    private float originY;

    public CategoryPanel(Category category, List<Module> modules, float x, float y) {
        this.category = category;
        setBounds(x, y, Theme.PANEL_WIDTH, 0f);

        // 行紧贴标题栏向下排列、行间无空隙（分隔线由行自身绘制），底部留 PANEL_BOTTOM_PADDING
        float cursor = Theme.PANEL_HEADER_HEIGHT;
        for (Module module : modules) {
            ModuleRow row = new ModuleRow(module);
            row.setBounds(x, y + cursor, Theme.PANEL_WIDTH, Theme.MODULE_HEIGHT);
            rows.add(row);
            add(row);
            cursor += Theme.MODULE_HEIGHT;
        }
        height = rows.isEmpty()
                ? Theme.PANEL_HEADER_HEIGHT
                : cursor + Theme.PANEL_BOTTOM_PADDING;
    }

    /** @return 本栏对应的分类 */
    public Category category() {
        return category;
    }

    /** @return 只读的模块行视图，顺序与显示顺序一致 */
    public List<ModuleRow> rows() {
        return Collections.unmodifiableList(rows);
    }

    /** @return 是否处于展开状态 */
    public boolean isExpanded() {
        return expanded;
    }

    /** 展开或折叠；折叠时隐藏全部行并把高度收为标题栏，展开时按行数还原高度。 */
    public void setExpanded(boolean value) {
        if (this.expanded == value) {
            return;
        }
        this.expanded = value;
        for (ModuleRow row : rows) {
            row.setVisible(value);
        }
        height = value ? expandedHeight() : Theme.PANEL_HEADER_HEIGHT;
    }

    /** 展开状态下的整栏高度：标题栏 + 各行 + 底部留白。 */
    private float expandedHeight() {
        if (rows.isEmpty()) {
            return Theme.PANEL_HEADER_HEIGHT;
        }
        return Theme.PANEL_HEADER_HEIGHT
                + rows.size() * Theme.MODULE_HEIGHT
                + Theme.PANEL_BOTTOM_PADDING;
    }

    /** 推进本栏各行的高亮动画；每帧调用一次。 */
    public void update(long nowMs) {
        for (ModuleRow row : rows) {
            row.update(nowMs);
        }
    }

    /**
     * 绘制整栏：圆角面板底色、标题栏（标题 + 折叠指示三角），最后是各行。
     *
     * <p>绘制序列对应 Epsilon AbstractDropdownPanel.drawBackground + ModuleButton.draw：
     * 圆角面板、{@code x + 10} 处的标题、右侧的折叠三角、直角模块行。
     */
    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        renderer.roundedRect(x, y, width, height, Theme.PANEL_RADIUS, Theme.SURFACE_CONTAINER);

        float titleY = y + (Theme.PANEL_HEADER_HEIGHT - renderer.textHeight(Theme.HEADER_TEXT_SIZE)) / 2f;
        renderer.text(category.name(), x + Theme.PANEL_TITLE_INSET, titleY, Theme.HEADER_TEXT_SIZE,
                Theme.TEXT_PRIMARY);
        drawTriangle(renderer, x + width - 10f, y + Theme.PANEL_HEADER_HEIGHT * 0.5f, expanded);

        super.render(renderer);
    }

    /**
     * 折叠指示三角：用三条横向矩形近似一个实心三角。
     *
     * <p>对应 Epsilon 的 {@code scope.triangle(x + width - 10, y + header/2, 3, expand, color)}；
     * DrawContext 没有三角原语，用矩形拼出向下（展开）或向右（折叠）的形态。
     */
    private void drawTriangle(Renderer renderer, float cx, float cy, boolean expanded) {
        Color color = Theme.TEXT_MUTED;
        if (expanded) {
            // 向下：宽 6 / 4 / 2 三行，高共 3px、垂直居中于 cy
            renderer.rect(cx - 3f, cy - 1.5f, 6f, 1f, color);
            renderer.rect(cx - 2f, cy - 0.5f, 4f, 1f, color);
            renderer.rect(cx - 1f, cy + 0.5f, 2f, 1f, color);
        } else {
            // 向右：高 6 / 4 / 2 三列，宽共 3px、水平居中于 cx
            renderer.rect(cx - 1.5f, cy - 3f, 1f, 6f, color);
            renderer.rect(cx - 0.5f, cy - 2f, 1f, 4f, color);
            renderer.rect(cx + 0.5f, cy - 1f, 1f, 2f, color);
        }
    }

    /**
     * 标题栏上按下左键只记录起点，真正的语义（折叠还是拖动）留到拖拽或释放时判定；
     * 其余位置照常下发给行。
     */
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && inHeader(mx, my)) {
            pressed = true;
            dragging = false;
            pressX = mx;
            pressY = my;
            originX = x;
            originY = y;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    /** 按住标题栏移动即拖动整列；未越过阈值前不移动，以免轻微抖动被当成拖动。 */
    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!pressed || button != 0) {
            return super.mouseDragged(mx, my, button, dx, dy);
        }
        if (!dragging
                && (Math.abs(mx - pressX) > DRAG_THRESHOLD || Math.abs(my - pressY) > DRAG_THRESHOLD)) {
            dragging = true;
        }
        if (dragging) {
            moveTo(originX + (float) (mx - pressX), originY + (float) (my - pressY));
        }
        return true;
    }

    /** 未拖动过的标题栏点击 = 折叠/展开。 */
    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (!pressed || button != 0) {
            return super.mouseReleased(mx, my, button);
        }
        boolean wasDragging = dragging;
        pressed = false;
        dragging = false;
        if (!wasDragging && inHeader(mx, my)) {
            setExpanded(!expanded);
        }
        return true;
    }

    /**
     * 把整列移动到新的左上角，所有行同步平移。
     *
     * <p>行持有绝对坐标、不会随父控件自动移动，因此这里必须显式平移，
     * 否则会出现「标题跟着走、内容留在原地」的错位。
     */
    public void moveTo(float newX, float newY) {
        float dx = newX - x;
        float dy = newY - y;
        setBounds(newX, newY, width, height);
        for (ModuleRow row : rows) {
            row.setBounds(row.x() + dx, row.y() + dy, row.width(), row.height());
        }
    }

    /** @return 点是否落在标题栏区域内（不可见时恒为 false） */
    private boolean inHeader(double mx, double my) {
        return visible && mx >= x && mx <= x + width && my >= y && my <= y + Theme.PANEL_HEADER_HEIGHT;
    }
}
