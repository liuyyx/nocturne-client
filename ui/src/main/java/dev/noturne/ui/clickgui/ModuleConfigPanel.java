package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Module;
import dev.noturne.client.value.BooleanValue;
import dev.noturne.client.value.ColorValue;
import dev.noturne.client.value.ModeValue;
import dev.noturne.client.value.NumberValue;
import dev.noturne.client.value.Value;
import dev.noturne.ui.component.Component;
import dev.noturne.ui.component.ColorPicker;
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
 * 单个模块的设置面板，从点击式 GUI 中唤出（右键模块行）。
 *
 * <p>按 {@link Value} 的类型逐个生成控件：布尔 → {@link ToggleSwitch}、数值 → {@link Slider}、
 * 枚举 → {@link ModeSelector}、颜色 → {@link ColorPicker}；没有设置项时显示一行提示而不是空白面板。
 */
public final class ModuleConfigPanel extends Panel {

    /** 面板宽度（像素）；与分类面板同宽，对齐 Epsilon 的 PANEL_WIDTH。 */
    private static final float WIDTH = Theme.PANEL_WIDTH;
    /** 内容与视口边缘保持的最小间距（像素）。 */
    private static final float MARGIN = Theme.PANEL_MARGIN;
    /** 面板内部滚动的每格像素数。 */
    private static final float SCROLL_STEP = 24f;

    /** 当前正在编辑的模块；为 {@code null} 表示面板尚未绑定模块。 */
    private Module module;
    /** 已生成的编辑控件，顺序与模块设置项一致。 */
    private final List<Component> editors = new ArrayList<Component>();
    /** 与 {@link #editors} 对齐的行标签；只有本身不带名称的控件（开关）才有标签，其余为 null。 */
    private final List<String> labels = new ArrayList<String>();
    /** 与 {@link #editors} 对齐的行基线 y（相对面板内容顶部，含标题栏高度）。 */
    private final List<Float> editorBaseY = new ArrayList<Float>();
    /** 绘制区域宽度（像素）；0 表示未知，此时不做横向夹取。 */
    private int viewportWidth;
    /** 绘制区域高度（像素）；0 表示未知，此时不做纵向夹取。 */
    private int viewportHeight;
    /** 内容总高度（含标题栏，不含底部留白）。 */
    private float contentHeight;
    /** 内部滚动偏移（像素）；0 表示停在顶部。 */
    private float scrollOffset;

    /** 构造面板；初始不可见，需由 {@link #show} 绑定模块后才显示。 */
    public ModuleConfigPanel() {
        setVisible(false);
    }

    /** @return 当前绑定的模块；尚未唤出时为 {@code null} */
    public Module module() {
        return module;
    }

    /** @return 已生成的编辑控件，顺序与模块设置项一致 */
    public List<Component> editors() {
        return Collections.unmodifiableList(editors);
    }

    /**
     * 重建编辑器并在给定屏幕位置显示。
     *
     * <p>每次都整体重建：模块的设置项是固定的，重建比增量更新更简单，也避免了
     * 「切到另一个模块后残留上一个模块控件」的问题。
     */
    public void show(Module module, float x, float y) {
        this.module = module;
        children.clear();
        editors.clear();
        labels.clear();
        editorBaseY.clear();
        scrollOffset = 0f;

        // 行内缩 SETTING_PADDING_X、行高 SETTING_HEIGHT、行间留 SETTING_GAP
        float cursor = Theme.PANEL_HEADER_HEIGHT;

        if (module != null) {
            for (Value<?> value : module.values()) {
                Component editor = create(value, x + Theme.SETTING_PADDING_X, y + cursor,
                        WIDTH - Theme.SETTING_PADDING_X * 2f);
                if (editor == null) {
                    continue;
                }
                editors.add(editor);
                // 开关与滑块自身不画设置名（滑块右侧画的是当前值），在行首补标签
                labels.add(value instanceof BooleanValue || value instanceof NumberValue
                        ? value.name() : null);
                editorBaseY.add(cursor);
                add(editor);
                cursor += Theme.SETTING_HEIGHT + Theme.SETTING_GAP;
            }
        }
        if (editors.isEmpty()) {
            cursor += Theme.SETTING_HEIGHT + Theme.SETTING_GAP;
        }
        contentHeight = cursor - Theme.SETTING_GAP;

        float height = contentHeight + Theme.PANEL_BOTTOM_PADDING;
        if (viewportHeight > 0) {
            // 面板高度不超过视口可用高度，超出部分改由内部滚动访问
            float maxHeight = viewportHeight - MARGIN * 2f;
            if (maxHeight > 0f && height > maxHeight) {
                height = maxHeight;
            }
        }
        float px = x;
        if (viewportWidth > 0) {
            // 夹取到视口内，避免唤出在屏幕外导致设置项点不到
            px = clamp(px, MARGIN, Math.max(MARGIN, viewportWidth - WIDTH - MARGIN));
        }
        float py = y;
        if (viewportHeight > 0) {
            py = clamp(py, MARGIN, Math.max(MARGIN, viewportHeight - height - MARGIN));
        }
        setBounds(px, py, WIDTH, height);
        applyScroll();
        setVisible(true);
    }

    /** 收起面板；同时复位编辑器的交互中间态，避免跨会话残留。 */
    public void hide() {
        setVisible(false);
        for (Component editor : editors) {
            editor.cancelInteractions();
        }
    }

    /** 同步绘制区域尺寸，供唤出定位与内部滚动使用；{@code 0} 表示未知、不限制对应轴。 */
    public void setViewport(int width, int height) {
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    /**
     * 更新各编辑控件的悬停状态并转发时钟；每帧调用一次。
     *
     * <p>{@code nowMs} 必须继续下传：开关等控件依赖它推进切换动画，丢弃时钟会让
     * 「点击了但界面没反应」（状态已改、动画冻死）。
     */
    public void update(long nowMs, double mouseX, double mouseY) {
        for (Component editor : editors) {
            editor.updateHover(mouseX, mouseY);
        }
        super.update(nowMs);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        float max = maxScroll();
        if (max <= 0f) {
            return false;
        }
        // 向上滚动（正增量）应把内容下移，即减小偏移
        scrollOffset = clamp(scrollOffset - (float) amount * SCROLL_STEP, 0f, max);
        applyScroll();
        return true;
    }

    /** @return 内部可滚动的最大偏移；内容未超出可视区域时为 0 */
    private float maxScroll() {
        float visible = height - Theme.PANEL_HEADER_HEIGHT - Theme.PANEL_BOTTOM_PADDING;
        float content = contentHeight - Theme.PANEL_HEADER_HEIGHT;
        return Math.max(0f, content - visible);
    }

    /** 按当前滚动偏移重新布置编辑器（绝对坐标布局，需显式平移）。 */
    private void applyScroll() {
        for (int i = 0; i < editors.size(); i++) {
            Component editor = editors.get(i);
            editor.setBounds(x + Theme.SETTING_PADDING_X, y + editorBaseY.get(i) - scrollOffset,
                    WIDTH - Theme.SETTING_PADDING_X * 2f, editor.height());
        }
    }

    /** 夹取到 {@code [lo, hi]}；区间反转时取中点，避免死夹。 */
    private static float clamp(float v, float lo, float hi) {
        if (lo > hi) {
            return (lo + hi) / 2f;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 按值的具体类型创建对应控件。
     *
     * @param value 模块的某个设置项
     * @return 对应的编辑控件；类型未知时返回 {@code null}（该设置项被跳过，不影响其他项）
     */
    private static Component create(Value<?> value, float x, float y, float width) {
        if (value instanceof BooleanValue) {
            final BooleanValue bool = (BooleanValue) value;
            ToggleSwitch toggle = new ToggleSwitch(bool.get(), v -> bool.set(v), bool::get);
            // 开关靠行尾对齐，在 SETTING_HEIGHT 行内垂直居中
            toggle.setBounds(x + width - Theme.SWITCH_WIDTH,
                    y + (Theme.SETTING_HEIGHT - Theme.SWITCH_HEIGHT) / 2f,
                    Theme.SWITCH_WIDTH, Theme.SWITCH_HEIGHT);
            return toggle;
        }
        if (value instanceof NumberValue) {
            final NumberValue number = (NumberValue) value;
            // 回读器：coerce（钳制/步长对齐）只有写入后才知道结果，控件必须以 value.get() 为准
            Slider slider = new Slider((float) number.min(), (float) number.max(),
                    number.get().floatValue(), f -> number.set(f.doubleValue()),
                    () -> number.get().floatValue());
            slider.setBounds(x, y, width, Theme.SETTING_HEIGHT);
            return slider;
        }
        if (value instanceof ModeValue) {
            ModeSelector selector = new ModeSelector((ModeValue) value);
            selector.setBounds(x, y, width, Theme.SETTING_HEIGHT);
            return selector;
        }
        if (value instanceof ColorValue) {
            final ColorValue color = (ColorValue) value;
            ColorPicker picker = new ColorPicker(color.get(), v -> color.set(v), color::argb);
            picker.setBounds(x, y, width, Theme.SETTING_HEIGHT);
            return picker;
        }
        return null;
    }

    /** 绘制面板底色、标题（模块名）、开关行标签与各编辑控件；无可用设置项时画一行提示。 */
    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        // 裁剪到面板范围：长设置名/标签不应绘制到面板之外；内部滚动的内容也在此被裁掉
        renderer.pushClip(x, y, width, height);
        try {
            renderer.roundedRect(x, y, width, height, Theme.PANEL_RADIUS, Theme.SURFACE_CONTAINER);
            String title = module == null ? "settings" : module.name();
            float titleY = y + (Theme.PANEL_HEADER_HEIGHT - renderer.textHeight(Theme.HEADER_TEXT_SIZE)) / 2f;
            renderer.text(title, x + Theme.PANEL_TITLE_INSET, titleY, Theme.HEADER_TEXT_SIZE,
                    Theme.TEXT_PRIMARY);

            // 开关控件本身不画名称，在行首补画设置项标签，与控件垂直居中对齐
            for (int i = 0; i < editors.size(); i++) {
                String label = labels.get(i);
                if (label == null) {
                    continue;
                }
                Component editor = editors.get(i);
                float labelY = editor.y()
                        + (editor.height() - renderer.textHeight(Theme.SETTING_TEXT_SIZE)) / 2f;
                renderer.text(label, x + Theme.SETTING_PADDING_X + Theme.ROW_CONTENT_INSET, labelY,
                        Theme.SETTING_TEXT_SIZE, Theme.TEXT_PRIMARY);
            }

            // 以「是否有可编辑控件」而非「值列表是否为空」判断：存在未支持类型时同样是空面板
            if (module != null && editors.isEmpty()) {
                renderer.text("no settings", x + Theme.SETTING_PADDING_X + Theme.ROW_CONTENT_INSET,
                        y + Theme.PANEL_HEADER_HEIGHT,
                        Theme.SETTING_TEXT_SIZE, Theme.TEXT_MUTED);
            }
            super.render(renderer);
        } finally {
            renderer.popClip();
        }
    }
}
