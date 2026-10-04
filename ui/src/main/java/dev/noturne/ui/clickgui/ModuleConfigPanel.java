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

    /** 面板宽度（像素）；规格表未作规定，沿用既有值。 */
    private static final float WIDTH = 178f;

    /** 当前正在编辑的模块；为 {@code null} 表示面板尚未绑定模块。 */
    private Module module;
    /** 已生成的编辑控件，顺序与模块设置项一致。 */
    private final List<Component> editors = new ArrayList<Component>();
    /** 与 {@link #editors} 对齐的行标签；只有本身不带名称的控件（开关）才有标签，其余为 null。 */
    private final List<String> labels = new ArrayList<String>();

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

        // 行内缩 OUTER_PADDING、行高 CONTROL_HEIGHT、行间留 ROW_GAP
        float cursor = Theme.HEADER_HEIGHT + Theme.SECTION_GAP;

        if (module != null) {
            for (Value<?> value : module.values()) {
                Component editor = create(value, x + Theme.OUTER_PADDING, y + cursor,
                        WIDTH - Theme.OUTER_PADDING * 2f);
                if (editor == null) {
                    continue;
                }
                editors.add(editor);
                labels.add(value instanceof BooleanValue ? value.name() : null);
                add(editor);
                cursor += Theme.CONTROL_HEIGHT + Theme.ROW_GAP;
            }
        }
        if (editors.isEmpty()) {
            cursor += Theme.CONTROL_HEIGHT + Theme.ROW_GAP;
        }
        setBounds(x, y, WIDTH, cursor - Theme.ROW_GAP + Theme.OUTER_PADDING);
        setVisible(true);
    }

    /** 收起面板；不清空已生成的控件，下次唤出时会被 {@link #show} 整体重建。 */
    public void hide() {
        setVisible(false);
    }

    /** 更新各编辑控件的悬停状态；每帧调用一次。 */
    public void update(long nowMs, double mouseX, double mouseY) {
        for (Component editor : editors) {
            editor.updateHover(mouseX, mouseY);
        }
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
            ToggleSwitch toggle = new ToggleSwitch(bool.get(), v -> bool.set(v));
            // 开关靠行尾对齐，在 18px 控件行内垂直居中
            toggle.setBounds(x + width - Theme.SWITCH_WIDTH,
                    y + (Theme.CONTROL_HEIGHT - Theme.SWITCH_HEIGHT) / 2f,
                    Theme.SWITCH_WIDTH, Theme.SWITCH_HEIGHT);
            return toggle;
        }
        if (value instanceof NumberValue) {
            final NumberValue number = (NumberValue) value;
            Slider slider = new Slider((float) number.min(), (float) number.max(),
                    number.get().floatValue(), f -> number.set(f.doubleValue()));
            slider.setBounds(x, y, width, Theme.CONTROL_HEIGHT);
            return slider;
        }
        if (value instanceof ModeValue) {
            ModeSelector selector = new ModeSelector((ModeValue) value);
            selector.setBounds(x, y, width, Theme.CONTROL_HEIGHT);
            return selector;
        }
        if (value instanceof ColorValue) {
            final ColorValue color = (ColorValue) value;
            ColorPicker picker = new ColorPicker(color.get(), v -> color.set(v));
            picker.setBounds(x, y, width, Theme.CONTROL_HEIGHT);
            return picker;
        }
        return null;
    }

    /** 绘制面板底色、标题（模块名）、开关行标签与各编辑控件；无设置项时画一行提示。 */
    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        renderer.roundedRect(x, y, width, height, Theme.PANEL_RADIUS, Theme.SURFACE_CONTAINER);
        String title = module == null ? "settings" : module.name();
        float titleY = y + (Theme.HEADER_HEIGHT - renderer.textHeight(Theme.FONT_SIZE)) / 2f;
        renderer.text(title, x + Theme.PANEL_TITLE_INSET, titleY, Theme.FONT_SIZE,
                Theme.TEXT_PRIMARY);

        // 开关控件本身不画名称，在行首补画设置项标签，与控件垂直居中对齐
        for (int i = 0; i < editors.size(); i++) {
            String label = labels.get(i);
            if (label == null) {
                continue;
            }
            Component editor = editors.get(i);
            float labelY = editor.y()
                    + (editor.height() - renderer.textHeight(Theme.FONT_SIZE_SMALL)) / 2f;
            renderer.text(label, x + Theme.OUTER_PADDING + Theme.ROW_CONTENT_INSET, labelY,
                    Theme.FONT_SIZE_SMALL, Theme.TEXT_PRIMARY);
        }

        if (module != null && module.values().isEmpty()) {
            renderer.text("no settings", x + Theme.OUTER_PADDING + Theme.ROW_CONTENT_INSET,
                    y + Theme.HEADER_HEIGHT + Theme.SECTION_GAP,
                    Theme.FONT_SIZE_SMALL, Theme.TEXT_MUTED);
        }
        super.render(renderer);
    }
}
