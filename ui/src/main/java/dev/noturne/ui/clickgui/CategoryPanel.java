package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.ui.component.Panel;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个分类栏：可折叠的标题栏 + 每个模块一行。
 *
 * <p>标题栏同时是拖动把手——按住并移动即整列跟随指针，原地按下再松开则折叠/展开。两者用位移阈值
 * 区分，因此「想拖动却触发了折叠」不会发生。折叠时整栏收窄为 42px 的窄条（参照 MD3 导航栏形态），
 * 展开时恢复 120px 宽。
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
        setBounds(x, y, Theme.RAIL_EXPANDED_WIDTH, 0f);

        // 行内缩 OUTER_PADDING，行间留 ROW_GAP，首行与标题栏之间留 SECTION_GAP
        float cursor = Theme.HEADER_HEIGHT + Theme.SECTION_GAP;
        for (Module module : modules) {
            ModuleRow row = new ModuleRow(module);
            row.setBounds(x + Theme.OUTER_PADDING, y + cursor,
                    Theme.RAIL_EXPANDED_WIDTH - Theme.OUTER_PADDING * 2f, Theme.CONTROL_HEIGHT);
            rows.add(row);
            add(row);
            cursor += Theme.CONTROL_HEIGHT + Theme.ROW_GAP;
        }
        height = rows.isEmpty() ? Theme.HEADER_HEIGHT : cursor - Theme.ROW_GAP + Theme.OUTER_PADDING;
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

    /** 展开或折叠；折叠时隐藏全部行并把整栏收窄为窄条，展开时按行数还原宽度与高度。 */
    public void setExpanded(boolean value) {
        if (this.expanded == value) {
            return;
        }
        this.expanded = value;
        for (ModuleRow row : rows) {
            row.setVisible(value);
        }
        if (value) {
            width = Theme.RAIL_EXPANDED_WIDTH;
            height = expandedHeight();
        } else {
            width = Theme.RAIL_COLLAPSED_WIDTH;
            height = Theme.HEADER_HEIGHT;
        }
    }

    /** 展开状态下的整栏高度：标题栏 + 分区间距 + 各行与行间距 + 底部内边距。 */
    private float expandedHeight() {
        if (rows.isEmpty()) {
            return Theme.HEADER_HEIGHT;
        }
        return Theme.HEADER_HEIGHT + Theme.SECTION_GAP
                + rows.size() * Theme.CONTROL_HEIGHT
                + (rows.size() - 1) * Theme.ROW_GAP
                + Theme.OUTER_PADDING;
    }

    /** 推进本栏各行的高亮动画；每帧调用一次。 */
    public void update(long nowMs) {
        for (ModuleRow row : rows) {
            row.update(nowMs);
        }
    }

    /** 绘制整栏：面板底色、标题栏（悬停时叠状态层、含折叠标记），最后是各行。 */
    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        renderer.roundedRect(x, y, width, height, Theme.PANEL_RADIUS, Theme.SURFACE_CONTAINER);

        // 标题栏悬停反馈用 MD3 状态层手法：在底色上叠一层低 alpha 的强调色
        if (hoveredHeader) {
            renderer.roundedRect(x, y, width, Theme.HEADER_HEIGHT, Theme.PANEL_RADIUS,
                    Theme.PRIMARY.withAlpha(Theme.STATE_LAYER_ALPHA));
        }
        float titleY = y + (Theme.HEADER_HEIGHT - renderer.textHeight(Theme.FONT_SIZE)) / 2f;
        // 折叠后栏宽收窄，标题裁剪到栏内，避免文字溢出到旁边的列
        renderer.pushClip(x, y, width, Theme.HEADER_HEIGHT);
        renderer.text(category.name(), x + Theme.PANEL_TITLE_INSET, titleY, Theme.FONT_SIZE,
                Theme.TEXT_PRIMARY);
        renderer.popClip();
        String mark = expanded ? "-" : "+";
        renderer.text(mark,
                x + width - Theme.ROW_CONTENT_INSET - renderer.textWidth(mark, Theme.FONT_SIZE),
                titleY, Theme.FONT_SIZE, Theme.TEXT_MUTED);

        super.render(renderer);
    }

    /** 指针是否悬停在标题栏上；与整列的悬停状态分开维护，标题栏高亮才能独立。 */
    private boolean hoveredHeader;

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
        return visible && mx >= x && mx <= x + width && my >= y && my <= y + Theme.HEADER_HEIGHT;
    }

    /** 与 {@link #hovered} 分开维护，让标题栏高亮拥有独立状态（悬停整列 ≠ 悬停标题栏）。 */
    public void updateHeaderHover(double mx, double my) {
        hoveredHeader = inHeader(mx, my);
    }
}
