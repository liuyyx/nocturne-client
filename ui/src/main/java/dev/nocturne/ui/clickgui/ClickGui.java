package dev.nocturne.ui.clickgui;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.ui.component.Panel;
import dev.nocturne.ui.gl.OverlayGui;
import dev.nocturne.ui.render.Renderer;
import dev.nocturne.ui.theme.Theme;

import io.github.humbleui.skija.Canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * 点击式 GUI：每个 {@link Category} 一列，自左向右排列。
 *
 * <p>默认关闭；打开后由 {@link dev.nocturne.ui.gl.GuiOverlay} 每帧驱动。关闭时既不绘制也不消费输入。
 * 交互约定：左键点模块行切换开关，左键按住标题栏拖动整列，右键点模块行唤出设置面板，
 * 滚轮整体上下滚动，Esc 关闭。
 */
public final class ClickGui extends Panel implements OverlayGui {

    /** Esc 键码（AWT VK_ESCAPE）。两代 LWJGL 的数值都不同，统一由输入后端翻译。 */
    private static final int KEY_ESCAPE = 27;
    /** 右键编号，用于唤出模块设置面板。 */
    private static final int BUTTON_RIGHT = 1;
    /** 内容与视口边缘保持的最小间距（像素）；对齐 Epsilon 的 PANEL_MARGIN_X/Y。 */
    private static final float MARGIN = Theme.PANEL_MARGIN;
    /** 相邻两个分类栏之间的间距（像素）；对齐 Epsilon 的 PANEL_GAP。 */
    private static final float COLUMN_GAP = Theme.PANEL_GAP;
    /** 滚轮每格移动的像素数；输入后端已把两代滚轮量纲归一化为「每格 ±1」。 */
    private static final float SCROLL_STEP = 24f;
    /** 横向平移后至少保留在视口内的宽度（像素），避免整列被拖出屏幕后无法找回。 */
    private static final float MIN_VISIBLE = 24f;

    /** 模块注册表，各分类栏的内容来源。 */
    private final ModuleRegistry registry;
    /** 分类栏列表，顺序即从左到右的排列顺序。 */
    private final List<CategoryPanel> panels = new ArrayList<CategoryPanel>();
    /** 已建成面板的内容版本号；与 {@link ModuleRegistry#revision()} 对不上就重建。 */
    private long builtRevision = -1L;
    /** 模块设置面板；仅在被唤出时可见，始终绘制在分类栏之上。 */
    private final ModuleConfigPanel configPanel = new ModuleConfigPanel();
    private boolean open;
    /** 绘制区域高度，由叠加层每帧同步；未知时为 0，此时滚动不做下界限制。 */
    private int viewportHeight;
    /** 绘制区域宽度，由叠加层每帧同步；未知时为 0，此时不做横向夹取/平移。 */
    private int viewportWidth;
    /** 本次左键手势是否从空白处开始；决定拖动是平移整体布局还是拖动某一列。 */
    private boolean panning;

    public ClickGui(ModuleRegistry registry) {
        this.registry = registry;
        buildPanels();
        // 最后添加 = 视觉最上层，也是输入派发的第一顺位
        add(configPanel);
    }

    /**
     * 按注册表现状重建分类栏：跳过空分类，并记录版本号。
     *
     * <p>空分类不得建列：空列只有标题栏占位，还会把靠右的列挤出屏幕。
     * 运行期新注册的模块靠 {@link #update} 里比对版本号发现，构造期只建一次不够。
     */
    private void buildPanels() {
        for (CategoryPanel panel : panels) {
            remove(panel);
        }
        panels.clear();
        float cursorX = MARGIN;
        float top = MARGIN;
        for (Category category : Category.values()) {
            if (registry.byCategory(category).isEmpty()) {
                continue;
            }
            CategoryPanel panel =
                    new CategoryPanel(category, registry.byCategory(category), cursorX, top);
            panels.add(panel);
            add(panel);
            cursorX += Theme.PANEL_WIDTH + COLUMN_GAP;
        }
        // 设置面板保持视觉最上层：重建把它被新列压住的顺序恢复。
        remove(configPanel);
        add(configPanel);
        builtRevision = registry.revision();
    }

    /** @return 界面当前是否打开；关闭时不绘制也不消费输入 */
    public boolean isOpen() {
        return open;
    }

    /** 直接设置开关状态；通常由 {@link #toggle()} 或 Esc 键驱动。 */
    public void setOpen(boolean value) {
        if (this.open == value) {
            return;
        }
        this.open = value;
        if (!value) {
            // 关闭时复位全树交互态并收起设置面板：关闭路径不止开关键一种（Esc、外部 setOpen、
            // 叠加层提前返回），丢失的释放事件会让 dragging/pressed 残留，重开后首次拖动即改写错值。
            cancelInteractions();
            configPanel.hide();
        }
    }

    /** 在打开与关闭之间切换；由 GUI 叠加层在检测到开关按键的按下沿时调用。 */
    public void toggle() {
        setOpen(!open);
    }

    /**
     * @return 各分类栏，顺序与显示顺序一致；只读视图——外部持有后重建不会撕裂渲染与命中
     */
    public List<CategoryPanel> panels() {
        return java.util.Collections.unmodifiableList(panels);
    }

    /**
     * 按注册表现状立即重建分类栏（跳过空分类）。
     *
     * <p>运行期批量注册后调一次即可；{@link #update} 里也会按版本号自动发现。
     */
    public void refresh() {
        buildPanels();
    }

    public ModuleRegistry registry() {
        return registry;
    }

    /** @return 模块设置面板，供测试与上层（如设置界面）直接访问 */
    public ModuleConfigPanel configPanel() {
        return configPanel;
    }

    /** 推进动画与悬停状态；每帧调用一次。 */
    public void update(long nowMs, double mouseX, double mouseY) {
        if (!open) {
            return;
        }
        if (registry.revision() != builtRevision) {
            buildPanels();
        }
        for (CategoryPanel panel : panels) {
            // 先更新悬停、再推进动画：否则动画使用上一帧的 hovered，高亮总是慢一帧
            for (ModuleRow row : panel.rows()) {
                row.updateHover(mouseX, mouseY);
            }
            panel.update(nowMs);
        }
        configPanel.update(nowMs, mouseX, mouseY);
    }

    @Override
    public void render(Renderer renderer) {
        if (!open) {
            return;
        }
        for (CategoryPanel panel : panels) {
            panel.render(renderer);
        }
        // 设置面板最后绘制，保证它压在分类栏之上
        configPanel.render(renderer);
    }

    /**
     * 叠加层的两通道渲染入口：本实现只用抽象绘制面，画布一律忽略。
     *
     * <p>（需要 Skija 画布的界面见 {@link dev.nocturne.ui.skija.SetsunaClickGui}；两个实现都挂在
     * {@link OverlayGui} 下，由 {@link dev.nocturne.ui.gl.GuiOverlay} 按后端能力选择。）
     */
    @Override
    public void render(Renderer renderer, Canvas canvas) {
        render(renderer);
    }

    /**
     * 右键落在模块行上时唤出该模块的设置面板，落在别处则收起。
     *
     * <p>右键必须在 {@code super} 之前拦截：分类栏与模块行都会把「点在自身范围内」视为已消费，
     * 等它们处理完就再也轮不到设置面板了。
     */

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!open) {
            return false;
        }
        if (button == BUTTON_RIGHT) {
            ModuleRow row = rowAt(mx, my);
            if (row != null) {
                configPanel.show(row.module(), row.x() + row.width() + Theme.SETTING_GAP, row.y());
            } else {
                configPanel.hide();
            }
            return true;
        }
        // 记录本次左键是否从空白处按下：只有空白起手才把后续拖动当作平移整个布局
        panning = hitTest(mx, my) == null;
        return super.mouseClicked(mx, my, button);
    }

    /**
     * 滚轮：指针在设置面板上时优先滚动面板内部，否则垂直滚动整个界面。
     *
     * <p>不做「每列各自滚动」：分类栏数量固定且可能整体超出屏幕，整体平移既符合直觉，
     * 也不会出现同一模块在不同列里高度错位的观感问题。
     */
    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!open) {
            return false;
        }
        if (configPanel.isVisible() && configPanel.contains(mx, my)
                && configPanel.mouseScrolled(mx, my, amount)) {
            return true;
        }
        // 向上滚动（正增量）应让内容下移，故取负号
        scrollBy((float) -amount * SCROLL_STEP);
        return true;
    }

    /**
     * 把全部分类栏垂直平移 {@code delta} 像素，并夹取到内容不越出视口。
     *
     * <p>按增量而非绝对偏移实现，因此与「拖动某一列」天然兼容——拖动改的是列的当前位置，
     * 滚动只在其上叠加位移，两者不会互相覆盖。
     */
    private void scrollBy(float delta) {
        if (delta == 0f || panels.isEmpty()) {
            return;
        }
        float minTop = Float.MAX_VALUE;
        float maxBottom = -Float.MAX_VALUE;
        for (CategoryPanel panel : panels) {
            minTop = Math.min(minTop, panel.y());
            maxBottom = Math.max(maxBottom, panel.bottom());
        }
        if (delta > 0f) {
            // 顶部夹取只允许减小位移；若已有列被拖出上边距，夹取结果不得反向把整列拽回
            float limit = MARGIN - minTop;
            if (limit < 0f) {
                limit = 0f;
            }
            if (delta > limit) {
                delta = limit;
            }
        } else if (viewportHeight > 0) {
            // 对称：底部夹取只允许减小（更负）的位移，绝不翻转方向
            float limit = viewportHeight - MARGIN - maxBottom;
            if (limit > 0f) {
                limit = 0f;
            }
            if (delta < limit) {
                delta = limit;
            }
        }
        if (delta == 0f) {
            return;
        }
        for (CategoryPanel panel : panels) {
            panel.moveTo(panel.x(), panel.y() + delta);
        }
        // 设置面板按唤出行锚定：列整体滚动后行已移位，不跟进就与锚定行脱钩（D9）。
        reanchorConfigPanel();
    }

    /**
     * 同步绘制区域尺寸，用于限制滚动范围、夹取拖动与进入平移。
     *
     * @param width  绘制区域宽度（像素）；0 表示未知，此时不做横向夹取
     * @param height 绘制区域高度（像素）；0 表示未知，此时不限制向下滚动
     */
    public void setViewport(int width, int height) {
        this.viewportWidth = width;
        this.viewportHeight = height;
        for (CategoryPanel panel : panels) {
            panel.setViewport(width, height);
        }
        configPanel.setViewport(width, height);
    }

    /**
     * 横向平移整个布局，使窄视口下最右侧的分类栏可达。
     *
     * <p>夹取到布局包围盒与视口至少有 {@link #MIN_VISIBLE} 宽的重叠，避免把全部内容推出屏幕。
     */
    private void panBy(float dx) {
        if (dx == 0f || panels.isEmpty()) {
            return;
        }
        if (viewportWidth <= 0) {
            // 视口未知时不做夹取（仍允许平移）
            for (CategoryPanel panel : panels) {
                panel.moveTo(panel.x() + dx, panel.y());
            }
            return;
        }
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        for (CategoryPanel panel : panels) {
            minX = Math.min(minX, panel.x());
            maxX = Math.max(maxX, panel.right());
        }
        float lo = MIN_VISIBLE - maxX;
        float hi = viewportWidth - MIN_VISIBLE - minX;
        if (lo > hi) {
            return;
        }
        float shift = dx < lo ? lo : (dx > hi ? hi : dx);
        if (shift == 0f) {
            return;
        }
        for (CategoryPanel panel : panels) {
            panel.moveTo(panel.x() + shift, panel.y());
        }
        // 横向平移同样会让锚定行移位，设置面板跟进（D9）。
        reanchorConfigPanel();
    }

    /**
     * 设置面板可见时，按其绑定模块的当前行位置重锚定。
     *
     * <p>面板用唤出时的行坐标定位；滚动/平移/列拖动后行已移位，不跟进就飘在原地与行脱钩。
     * 找不到行（模块被卸载/列被重建）时保持原位，不强行收起——收起会丢掉用户正在调的值。
     */
    private void reanchorConfigPanel() {
        if (!configPanel.isVisible() || configPanel.module() == null) {
            return;
        }
        ModuleRow row = rowFor(configPanel.module());
        if (row == null) {
            return;
        }
        configPanel.show(configPanel.module(), row.x() + row.width() + Theme.SETTING_GAP, row.y());
    }

    /** @return 绑定该模块的行；找不到返回 null */
    private ModuleRow rowFor(dev.nocturne.client.module.Module module) {
        for (CategoryPanel panel : panels) {
            for (ModuleRow row : panel.rows()) {
                if (row.module() == module) {
                    return row;
                }
            }
        }
        return null;
    }

    /**
     * @return 指针下的模块行；不在任何行上时返回 null
     *
     * <p>遍历顺序与 {@link Panel#hitTest} 一致：后添加的分类栏在视觉上层、优先命中；
     * 设置面板永远最上层，其覆盖区域内不再命中下层模块行。否则左键（走命中测试）
     * 与右键（走本方法）在重叠区会命中不同的行，两个按钮语义相反。
     */
    private ModuleRow rowAt(double mx, double my) {
        if (configPanel.isVisible() && configPanel.contains(mx, my)) {
            return null;
        }
        for (int i = panels.size() - 1; i >= 0; i--) {
            CategoryPanel panel = panels.get(i);
            if (!panel.isVisible() || !panel.contains(mx, my)) {
                continue;
            }
            List<ModuleRow> rows = panel.rows();
            for (int j = rows.size() - 1; j >= 0; j--) {
                ModuleRow row = rows.get(j);
                if (row.isVisible() && row.contains(mx, my)) {
                    return row;
                }
            }
        }
        return null;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        // 平移手势：空白起手才整体平移，否则交给列拖动；列拖动后锚定行已移位，面板跟进（D9）。
        if (open && panning && button == 0) {
            panBy((float) dx);
        }
        boolean consumed = super.mouseDragged(mx, my, button, dx, dy);
        reanchorConfigPanel();
        return consumed;
    }

    /**
     * 关闭时不消费输入，让事件继续下传（例如交给游戏处理）。
     *
     * <p>只有左键释放才清平移态（D10）：右键释放（收起面板那类操作）不得中断正在进行的左键平移。
     */
    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) {
            panning = false;
        }
        return open && super.mouseReleased(mx, my, button);
    }

    @Override
    public void cancelInteractions() {
        super.cancelInteractions();
        panning = false;
    }
    /** 目前只处理 Esc（关闭界面）；其余按键交给子控件。 */
    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        if (!open) {
            return false;
        }
        if (keyCode == KEY_ESCAPE) {
            setOpen(false);
            return true;
        }
        return super.keyPressed(keyCode, modifiers);
    }
}
