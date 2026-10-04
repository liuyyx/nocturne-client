package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.ModuleRegistry;
import dev.noturne.ui.component.Panel;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * 点击式 GUI：每个 {@link Category} 一列，自左向右排列。
 *
 * <p>默认关闭；打开后由 {@link dev.noturne.ui.gl.GuiOverlay} 每帧驱动。关闭时既不绘制也不消费输入。
 * 交互约定：左键点模块行切换开关，左键按住标题栏拖动整列，右键点模块行唤出设置面板，
 * 滚轮整体上下滚动，Esc 关闭。
 */
public final class ClickGui extends Panel {

    /** GLFW 的 Esc 键码。 */
    private static final int KEY_ESCAPE = 256;
    /** 右键编号，用于唤出模块设置面板。 */
    private static final int BUTTON_RIGHT = 1;
    /** 内容与视口边缘保持的最小间距（像素）。 */
    private static final float MARGIN = 12f;
    /** 相邻两个分类栏之间的间距（像素）；规格表未作规定，取略大于栏内间距的值以区分成组。 */
    private static final float COLUMN_GAP = 8f;
    /** 滚轮每格移动的像素数；游戏上报的滚轮增量通常为 ±1。 */
    private static final float SCROLL_STEP = 24f;

    /** 模块注册表，各分类栏的内容来源。 */
    private final ModuleRegistry registry;
    /** 分类栏列表，顺序即从左到右的排列顺序。 */
    private final List<CategoryPanel> panels = new ArrayList<CategoryPanel>();
    /** 模块设置面板；仅在被唤出时可见，始终绘制在分类栏之上。 */
    private final ModuleConfigPanel configPanel = new ModuleConfigPanel();
    private boolean open;
    /** 绘制区域高度，由叠加层每帧同步；未知时为 0，此时滚动不做下界限制。 */
    private int viewportHeight;

    public ClickGui(ModuleRegistry registry) {
        this.registry = registry;

        float cursorX = MARGIN;
        float top = MARGIN;
        for (Category category : Category.values()) {
            CategoryPanel panel =
                    new CategoryPanel(category, registry.byCategory(category), cursorX, top);
            panels.add(panel);
            add(panel);
            cursorX += Theme.RAIL_EXPANDED_WIDTH + COLUMN_GAP;
        }
        // 最后添加 = 视觉最上层，也是输入派发的第一顺位
        add(configPanel);
    }

    /** @return 界面当前是否打开；关闭时不绘制也不消费输入 */
    public boolean isOpen() {
        return open;
    }

    /** 直接设置开关状态；通常由 {@link #toggle()} 或 Esc 键驱动。 */
    public void setOpen(boolean value) {
        this.open = value;
    }

    /** 在打开与关闭之间切换；由 GUI 叠加层在检测到开关按键的按下沿时调用。 */
    public void toggle() {
        open = !open;
    }

    /** @return 各分类栏，顺序与显示顺序一致 */
    public List<CategoryPanel> panels() {
        return panels;
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
        for (CategoryPanel panel : panels) {
            panel.update(nowMs);
            panel.updateHeaderHover(mouseX, mouseY);
            for (ModuleRow row : panel.rows()) {
                row.updateHover(mouseX, mouseY);
            }
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
                configPanel.show(row.module(), row.x() + row.width() + Theme.ROW_GAP, row.y());
            } else {
                configPanel.hide();
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    /**
     * 滚轮垂直滚动整个界面。
     *
     * <p>不做「每列各自滚动」：分类栏数量固定且可能整体超出屏幕，整体平移既符合直觉，
     * 也不会出现同一模块在不同列里高度错位的观感问题。
     */
    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!open) {
            return false;
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
        if (delta > 0f && minTop + delta > MARGIN) {
            delta = MARGIN - minTop;
        }
        if (delta < 0f && viewportHeight > 0 && maxBottom + delta < viewportHeight - MARGIN) {
            delta = viewportHeight - MARGIN - maxBottom;
        }
        if (delta == 0f) {
            return;
        }
        for (CategoryPanel panel : panels) {
            panel.moveTo(panel.x(), panel.y() + delta);
        }
    }

    /**
     * 同步绘制区域高度，用于限制滚动范围。
     *
     * @param height 绘制区域高度（像素）；0 表示未知，此时不限制向下滚动
     */
    public void setViewport(int height) {
        this.viewportHeight = height;
    }

    /** @return 指针下的模块行；不在任何行上时返回 null */
    private ModuleRow rowAt(double mx, double my) {
        for (CategoryPanel panel : panels) {
            for (ModuleRow row : panel.rows()) {
                if (row.isVisible() && row.contains(mx, my)) {
                    return row;
                }
            }
        }
        return null;
    }

    /** 关闭时不消费输入，让事件继续下传（例如交给游戏处理）。 */
    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return open && super.mouseReleased(mx, my, button);
    }

    /** 关闭时不消费输入；打开时下发给分类栏（拖动标题栏）与设置面板（拖动滑块）。 */
    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return open && super.mouseDragged(mx, my, button, dx, dy);
    }

    /** 目前只处理 Esc（关闭界面）；其余按键交给子控件。 */
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
