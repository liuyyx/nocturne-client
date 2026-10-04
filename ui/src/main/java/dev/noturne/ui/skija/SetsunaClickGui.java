package dev.noturne.ui.skija;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.module.ModuleRegistry;
import dev.noturne.client.value.BooleanValue;
import dev.noturne.client.value.ColorValue;
import dev.noturne.client.value.ModeValue;
import dev.noturne.client.value.NumberValue;
import dev.noturne.client.value.Value;
import dev.noturne.ui.gl.OverlayGui;
import dev.noturne.ui.render.Renderer;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Setsuna 风格的点击式界面：**三栏**（分类导航 / 模块列表 / 设置详情），直接用 Skija 画布绘制。
 *
 * <p>与 {@link dev.noturne.ui.clickgui.ClickGui}（四列、面向 {@code Renderer} 抽象）的关系：
 * 两者并存，由叠加层按后端能力选择——Skija 可用时用本类（能画玻璃层、阴影、图标字体，视觉与
 * 上游一致），否则回落到四列界面。本类不继承组件树，因为组件树的原语表达不了这些效果
 * （见 {@link SkijaControls} 的说明）。
 *
 * <p>绘制原语与视觉令牌全部来自移植件：几何用 {@link ClickGuiLayout}，控件与配色用
 * {@link SkijaControls} / {@link SkijaTheme} / {@link SkijaUi}，分类图标用 {@link CategoryGlyphs}
 * （Lucide 图标字体）。这些文件均移植自 Setsuna，许可见 THIRD-PARTY-NOTICES.md。
 *
 * <p>交互：左键点分类切换栏目；左键点模块行的**右侧圆点**切换启用、点行的其余部分选中模块；
 * 设置区里布尔是胶囊、数值是轨道（可拖）、模式点一下循环、颜色点色块展开 H/S/V 调色、
 * **右键任意设置项恢复默认值**；滚轮在模块列表与设置区分别滚动；Esc 关闭（叠加层也会派发）。
 */
public final class SetsunaClickGui implements OverlayGui {

    /** Esc 键码（AWT VK_ESCAPE）。 */
    private static final int KEY_ESCAPE = 27;
    /** 右键编号，用于「恢复默认值」。 */
    private static final int BUTTON_RIGHT = 1;

    /** 分类导航项的边长与间距。 */
    private static final float RAIL_ITEM = 30f;
    private static final float RAIL_ITEM_TOP = 6f;
    /** 模块行高度与行间距。 */
    private static final float MODULE_ROW_HEIGHT = 26f;
    private static final float MODULE_ROW_GAP = 3f;
    /** 模块行右侧「启用圆点」的命中宽度（像素）。 */
    private static final float MODULE_TOGGLE_HIT_WIDTH = 30f;
    /** 列表标题占用的高度（"Modules" 那行）。 */
    private static final float LIST_TITLE_HEIGHT = 20f;
    /** 设置区单行的高度。 */
    private static final float SETTING_ROW_HEIGHT = 34f;
    /** 数值滑块的轨道高度。 */
    private static final float TRACK_HEIGHT = 4f;
    /** 布尔开关胶囊的尺寸。 */
    private static final float PILL_WIDTH = 30f;
    private static final float PILL_HEIGHT = 16f;
    /** 颜色色块的尺寸。 */
    private static final float SWATCH_WIDTH = 34f;
    private static final float SWATCH_HEIGHT = 14f;
    /** 颜色编辑器里每条分量滑块的高度与间距。 */
    private static final float COLOR_SLIDER_HEIGHT = 12f;
    private static final float COLOR_SLIDER_GAP = 5f;

    /** 模块注册表：栏目与模块的来源。 */
    private final ModuleRegistry registry;
    /** 参与显示的分类（按枚举顺序，跳过没有模块的分类）。 */
    private final List<Category> categories = new ArrayList<Category>();

    private boolean open;
    private int viewportWidth;
    private int viewportHeight;
    /** 当前选中的分类；{@code null} 表示还没有可选分类。 */
    private Category activeCategory;
    /** 当前选中的模块；{@code null} 表示详情栏显示占位提示。 */
    private Module selectedModule;
    /** 最近一次指针位置（GUI 坐标）。 */
    private double mouseX;
    private double mouseY;
    /** 模块列表与设置区各自的滚动偏移（负数表示内容上移）。 */
    private float listScroll;
    private float detailScroll;
    /** 本帧布局；输入与绘制共用同一份，避免两处各算一次导致热区与视觉错位。 */
    private ClickGuiLayout layout;

    /** 正在拖动的对象类型。 */
    private enum DragKind {
        /** 没有拖动。 */
        NONE,
        /** 数值设置项的轨道。 */
        NUMBER,
        /** 颜色编辑器：色相。 */
        COLOR_H,
        /** 颜色编辑器：饱和度。 */
        COLOR_S,
        /** 颜色编辑器：明度。 */
        COLOR_V
    }

    private DragKind dragKind = DragKind.NONE;
    /** 正在拖动的数值设置项（{@link DragKind#NUMBER} 时有效）。 */
    private NumberValue draggingNumber;
    /** 正在调色的颜色项（颜色拖动时有效）。 */
    private ColorValue draggingColor;
    /** 拖动开始时滑块轨道的左边界与宽度（两种拖动共用）。 */
    private float draggingTrackX;
    private float draggingTrackWidth;
    /** 展开调色板的颜色项；{@code null} 表示没有展开。 */
    private ColorValue expandedColor;

    /** 点「编辑 HUD」时的回调；由叠加层接到 HUD 编辑器（{@code null} 时该按钮是空操作）。 */
    private Runnable onEditHud;

    /** 设置「编辑 HUD」回调。 */
    public void setOnEditHud(Runnable callback) {
        this.onEditHud = callback;
    }

    /**
     * @param registry 模块注册表
     */
    public SetsunaClickGui(ModuleRegistry registry) {
        this.registry = registry;
        refreshCategories();
    }

    /** 重新计算参与显示的分类：没有模块的分类不占位（与组件树界面的约定一致）。 */
    private void refreshCategories() {
        categories.clear();
        for (Category category : Category.values()) {
            if (!registry.byCategory(category).isEmpty()) {
                categories.add(category);
            }
        }
        if (activeCategory == null || !categories.contains(activeCategory)) {
            activeCategory = categories.isEmpty() ? null : categories.get(0);
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void setOpen(boolean value) {
        if (this.open == value) {
            return;
        }
        this.open = value;
        if (!value) {
            cancelInteractions();
        }
    }

    @Override
    public void toggle() {
        setOpen(!open);
    }

    @Override
    public void setViewport(int width, int height) {
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    @Override
    public void update(long nowMs, double mouseX, double mouseY) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        if (!open) {
            return;
        }
        // 分类可能在运行期增删（模块注册表可变），每次打开后重算一次成本很低
        refreshCategories();
        layout = currentLayout();
    }

    /** 按当前视口算布局；视口未知时用 854×480 兜底，避免首帧出现零尺寸面板。 */
    private ClickGuiLayout currentLayout() {
        int width = viewportWidth > 0 ? viewportWidth : 854;
        int height = viewportHeight > 0 ? viewportHeight : 480;
        return ClickGuiLayout.of(width, height, 1.0f);
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Renderer renderer, Canvas canvas) {
        if (!open || canvas == null) {
            return;
        }
        if (layout == null) {
            layout = currentLayout();
        }
        draw(canvas);
    }

    /** 一帧的完整绘制：背板 → 面板 → 三栏 → 内容。 */
    private void draw(Canvas canvas) {
        int width = Math.round(viewportWidth > 0 ? viewportWidth : 854);
        int height = Math.round(viewportHeight > 0 ? viewportHeight : 480);
        // 背板：SkijaBackdrop 的动画背景（渐变 + 网格或自定义图 + 扫描线 + 视差 + 边缘压暗），
        // shadeAlpha 用 180 ≈ 原来那层背板的不透明度。上游独立屏幕同样是「背景 + 压暗」两段。
        SkijaBackdrop.draw(canvas, width, height, 180);
        SkijaControls.panel(canvas, panelBox());
        drawHeader(canvas);
        drawSeparators(canvas);
        drawRail(canvas);
        drawModuleList(canvas);
        drawDetail(canvas);
    }

    private SkijaControls.Box panelBox() {
        return new SkijaControls.Box(layout.x(), layout.y(), layout.width(), layout.height());
    }

    /** 标题栏：品牌名（字距排版）+ 强调色圆点 + 「编辑 HUD」按钮 + 右侧提示。 */
    private void drawHeader(Canvas canvas) {
        float headerHeight = layout.headerHeight();
        float top = layout.y();
        int textColor = SkijaControls.TEXT;
        float size = 11.0f;
        float tracking = 2.0f;
        float x = layout.x() + 14f;
        SkijaControls.disc(canvas, x, top + headerHeight * 0.5f - 3f, 6f, SkijaTheme.accent());
        x += 12f;
        SkijaControls.brand(canvas, "NOTURNE", x, top, headerHeight, textColor, size, tracking);

        SkijaControls.Box editBox = editHudButton();
        SkijaControls.button(canvas, editBox, "编辑 HUD", editBox.contains(mouseX, mouseY), true,
                SkijaControls.Tone.NORMAL);
        String hint = "ESC 关闭 · 右键重置";
        float hintWidth = SkijaUi.textWidth(hint, 8.5f);
        SkijaUi.text(canvas, hint, editBox.x - hintWidth - 12f, top, headerHeight,
                SkijaControls.TEXT_FAINT, 8.5f);
        // 标题栏底部一条分隔线：用弱描边的低透明，替代上游的整块 header 底色
        SkijaUi.fill(canvas, layout.x() + 1f, top + headerHeight, layout.width() - 2f, 1f,
                SkijaTheme.withAlpha(SkijaTheme.BORDER_SOFT, 90));
    }

    /** 标题栏「编辑 HUD」按钮的几何；绘制与命中测试共用，避免两处各算一次导致点不准。 */
    private SkijaControls.Box editHudButton() {
        float buttonWidth = 64f;
        float buttonHeight = Math.min(18f, Math.max(12f, layout.headerHeight() - 10f));
        return new SkijaControls.Box(layout.x() + layout.width() - buttonWidth - 10f,
                layout.y() + (layout.headerHeight() - buttonHeight) * 0.5f,
                buttonWidth, buttonHeight);
    }

    /** 三栏之间的两条竖直分隔线。 */
    private void drawSeparators(Canvas canvas) {
        float bodyTop = layout.bodyY();
        float bodyHeight = layout.bodyHeight();
        int color = SkijaTheme.withAlpha(SkijaTheme.BORDER_SOFT, 110);
        SkijaUi.fill(canvas, layout.x() + layout.railWidth(), bodyTop, 1f, bodyHeight, color);
        SkijaUi.fill(canvas, layout.detailX(), bodyTop, 1f, bodyHeight, color);
    }

    /** 左栏：分类图标（选中与悬停用强调色/更亮的底）。 */
    private void drawRail(Canvas canvas) {
        float x = layout.x() + (layout.railWidth() - RAIL_ITEM) * 0.5f;
        float y = layout.y() + layout.categoryTop();
        for (Category category : categories) {
            SkijaControls.Box box = new SkijaControls.Box(x, y, RAIL_ITEM, RAIL_ITEM);
            boolean selected = category == activeCategory;
            boolean hovered = box.contains(mouseX, mouseY);
            int fill;
            if (selected) {
                fill = SkijaTheme.withAlpha(SkijaTheme.accent(), 48);
            } else if (hovered) {
                fill = SkijaControls.CARD_HOVER;
            } else {
                fill = SkijaControls.CARD;
            }
            int stroke = selected ? SkijaTheme.withAlpha(SkijaTheme.accent(), 170) : SkijaControls.STROKE;
            SkijaControls.surface(canvas, box, fill, stroke, SkijaControls.RADIUS_SMALL);
            int iconColor = selected ? SkijaTheme.accent() : SkijaControls.TEXT_MUTED;
            SkijaUi.icon(canvas, CategoryGlyphs.forCategory(category), box.x() + 8f, box.y() + 7f,
                    16f, iconColor, 16f, SkijaUi.IconSet.LUCIDE);
            y += RAIL_ITEM + RAIL_ITEM_TOP;
        }
    }

    /** 中栏：模块列表（可滚动）。行的右侧圆点表示启用状态，点它切换启用。 */
    private void drawModuleList(Canvas canvas) {
        float listX = layout.moduleX();
        float listY = layout.moduleListY();
        float listWidth = layout.moduleWidth();
        float listHeight = layout.moduleListHeight();
        if (listHeight <= 1f) {
            return;
        }
        List<Module> modules = modulesOfActive();

        canvas.save();
        canvas.clipRect(Rect.makeXYWH(listX, layout.bodyY(), listWidth, layout.bodyHeight()),
                ClipMode.INTERSECT);
        SkijaUi.text(canvas, titleOfActive(), listX + 10f, listY - LIST_TITLE_HEIGHT + 2f,
                LIST_TITLE_HEIGHT - 6f, SkijaControls.TEXT_FAINT, 8.5f);

        float rowY = listY + listScroll;
        for (Module module : modules) {
            SkijaControls.Box row = new SkijaControls.Box(listX + 6f, rowY, listWidth - 12f,
                    MODULE_ROW_HEIGHT);
            boolean hovered = row.contains(mouseX, mouseY);
            boolean selected = module == selectedModule;
            SkijaControls.menuItem(canvas, row, module.name(), hovered || selected,
                    selected ? SkijaControls.Tone.PRIMARY : SkijaControls.Tone.NORMAL);
            // 启用状态：圆点在行右端；命中区比视觉更大，避免要求像素级精确点击
            float dotX = row.x() + row.width() - 14f;
            float dotY = row.y() + row.height() * 0.5f - 3f;
            int dotColor = module.isEnabled() ? SkijaTheme.accent() : SkijaTheme.BORDER_STRONG;
            SkijaControls.disc(canvas, dotX, dotY, 6f, dotColor);
            rowY += MODULE_ROW_HEIGHT + MODULE_ROW_GAP;
        }
        if (modules.isEmpty()) {
            SkijaUi.text(canvas, "该分类暂无模块", listX + 10f, listY + 4f, 16f,
                    SkijaControls.TEXT_FAINT, 8.5f);
        }
        canvas.restore();
    }

    private List<Module> modulesOfActive() {
        return activeCategory == null ? new ArrayList<Module>() : registry.byCategory(activeCategory);
    }

    private String titleOfActive() {
        return activeCategory == null ? "MODULES" : activeCategory.name();
    }

    /** 右栏：选中模块的启用开关 + 全部设置项。 */
    private void drawDetail(Canvas canvas) {
        float detailX = layout.detailX();
        float width = layout.detailWidth();
        float top = layout.settingsY();
        float height = layout.settingsHeight();
        if (width <= 1f || height <= 1f) {
            return;
        }
        canvas.save();
        canvas.clipRect(Rect.makeXYWH(detailX, layout.bodyY(), width, layout.bodyHeight()),
                ClipMode.INTERSECT);

        Module module = selectedModule;
        if (module == null) {
            SkijaUi.text(canvas, "选择左侧的模块以查看设置", detailX + 12f, top + 6f, 18f,
                    SkijaControls.TEXT_FAINT, 8.5f);
            canvas.restore();
            return;
        }

        float x = detailX + 12f;
        float innerWidth = width - 24f;
        float y = top + detailScroll;

        // 模块名 + 分类
        SkijaUi.boldText(canvas, module.name(), x, y, 20f, SkijaControls.TEXT, 11f);
        y += 22f;
        SkijaUi.text(canvas, String.valueOf(module.category()), x, y, 14f,
                SkijaControls.TEXT_FAINT, 8f);
        y += 20f;

        // 启用按钮
        SkijaControls.Box enableBox = new SkijaControls.Box(x, y, innerWidth, 24f);
        boolean enableHovered = enableBox.contains(mouseX, mouseY);
        SkijaControls.button(canvas, enableBox, module.isEnabled() ? "已启用" : "已禁用",
                enableHovered, true,
                module.isEnabled() ? SkijaControls.Tone.PRIMARY : SkijaControls.Tone.NORMAL);
        y += 30f;

        List<Value<?>> values = module.values();
        if (values.isEmpty()) {
            SkijaUi.text(canvas, "该模块没有可调设置", x, y, 16f, SkijaControls.TEXT_FAINT, 8.5f);
            canvas.restore();
            return;
        }
        for (Value<?> value : values) {
            y = drawSetting(canvas, value, x, y, innerWidth);
        }
        canvas.restore();
    }

    /**
     * 画一行设置项，返回下一行的 y。
     *
     * <p>行高按控件类型决定（颜色项展开调色板时会变高），因此绘制与命中测试都必须走
     * {@link #settingRowHeight(Value)}——两处若各自算一次，展开后下面的行就会错位。
     *
     * <p>按值的运行时类型分派：布尔是胶囊开关、数值是轨道滑块、模式是左右循环、颜色是色块 +
     * 可展开的调色板。用 {@code instanceof} 而不是引入访问者接口，是为了让值框架不认识 UI。
     */
    private float drawSetting(Canvas canvas, Value<?> value, float x, float y, float width) {
        SkijaUi.text(canvas, value.name(), x, y, 14f, SkijaControls.TEXT_MUTED, 8.5f);
        if (value instanceof BooleanValue) {
            BooleanValue bool = (BooleanValue) value;
            SkijaControls.Box pill = new SkijaControls.Box(x + width - PILL_WIDTH, y - 1f,
                    PILL_WIDTH, PILL_HEIGHT);
            drawPill(canvas, pill, bool.get().booleanValue());
            return y + SETTING_ROW_HEIGHT;
        }
        if (value instanceof NumberValue) {
            NumberValue number = (NumberValue) value;
            drawValueLabel(canvas, number.display(), x, y, width, SkijaControls.TEXT);
            float trackY = y + 16f;
            drawTrack(canvas, x, trackY, width, fractionOf(number), SkijaTheme.accent(), true);
            return y + SETTING_ROW_HEIGHT;
        }
        if (value instanceof ModeValue) {
            ModeValue mode = (ModeValue) value;
            String shown = mode.display();
            float shownWidth = SkijaUi.textWidth(shown, 8.5f);
            SkijaUi.text(canvas, "<", x + width - shownWidth - 18f, y, 14f,
                    SkijaControls.TEXT_FAINT, 9f);
            SkijaUi.text(canvas, shown, x + width - shownWidth - 8f, y, 14f,
                    SkijaTheme.accent(), 8.5f);
            SkijaUi.text(canvas, ">", x + width - 6f, y, 14f, SkijaControls.TEXT_FAINT, 9f);
            return y + SETTING_ROW_HEIGHT;
        }
        if (value instanceof ColorValue) {
            ColorValue color = (ColorValue) value;
            boolean expanded = color == expandedColor;
            SkijaControls.Box swatch = new SkijaControls.Box(x + width - SWATCH_WIDTH, y - 2f,
                    SWATCH_WIDTH, SWATCH_HEIGHT);
            int stroke = expanded ? SkijaTheme.accent() : SkijaControls.STROKE_STRONG;
            SkijaUi.rounded(canvas, swatch.x, swatch.y, swatch.width, swatch.height, 3f, stroke);
            SkijaUi.rounded(canvas, swatch.x + 1f, swatch.y + 1f, swatch.width - 2f,
                    swatch.height - 2f, 2f, color.argb());
            drawValueLabel(canvas, color.display(), x, y, width - SWATCH_WIDTH - 6f,
                    SkijaControls.TEXT_MUTED);
            if (expanded) {
                drawColorEditor(canvas, color, x, y + SETTING_ROW_HEIGHT - 6f, width);
            }
            return y + settingRowHeight(value);
        }
        // 未知值类型：只显示文本，不提供交互（新增值类型时在这里补控件）
        drawValueLabel(canvas, value.display(), x, y, width, SkijaControls.TEXT_MUTED);
        return y + SETTING_ROW_HEIGHT;
    }

    /** 行右对齐的值文本（自动按可用宽度省略）。 */
    private void drawValueLabel(Canvas canvas, String text, float x, float y, float width,
                                int color) {
        String shown = SkijaControls.ellipsize(text, Math.max(0f, width - 4f));
        float shownWidth = SkijaUi.textWidth(shown, 8.5f);
        SkijaUi.text(canvas, shown, x + width - shownWidth, y, 14f, color, 8.5f);
    }

    /**
     * 一行设置项占用的高度。
     *
     * <p>数值/布尔/模式固定一行；颜色项展开调色板时多出三条分量滑块。
     */
    private float settingRowHeight(Value<?> value) {
        if (value instanceof ColorValue && value == expandedColor) {
            return SETTING_ROW_HEIGHT + 3f * (COLOR_SLIDER_HEIGHT + COLOR_SLIDER_GAP) + 2f;
        }
        return SETTING_ROW_HEIGHT;
    }

    /** 数值/颜色共用的轨道：底色 + 按比例的填充 + 手柄。 */
    private void drawTrack(Canvas canvas, float x, float y, float width, float fraction,
                           int fillColor, boolean handle) {
        SkijaUi.rounded(canvas, x, y, width, TRACK_HEIGHT, TRACK_HEIGHT * 0.5f, SkijaControls.CARD);
        SkijaUi.rounded(canvas, x, y, Math.max(TRACK_HEIGHT, width * fraction), TRACK_HEIGHT,
                TRACK_HEIGHT * 0.5f, fillColor);
        if (handle) {
            SkijaControls.disc(canvas, x + width * fraction - 4f, y - 2.5f, 9f, fillColor);
        }
    }

    /**
     * 颜色编辑器：H（六段彩虹）/S/V（按当前另外两个分量的渐变）三条可拖滑块。
     *
     * <p>用 HSV 而不是 RGB：三个分量相互独立且渐变轨能直接显示"拖到这儿会是什么颜色"，
     * 这是调色界面的通用做法。
     */
    private void drawColorEditor(Canvas canvas, ColorValue color, float x, float y, float width) {
        float[] hsv = toHsv(color.argb());
        float labelWidth = 11f;
        float valueWidth = 24f;
        float trackX = x + labelWidth;
        float trackWidth = Math.max(24f, width - labelWidth - valueWidth - 6f);
        String[] labels = {"H", "S", "V"};
        for (int channel = 0; channel < 3; channel++) {
            float rowY = y + channel * (COLOR_SLIDER_HEIGHT + COLOR_SLIDER_GAP);
            SkijaUi.text(canvas, labels[channel], x, rowY - 1f, COLOR_SLIDER_HEIGHT,
                    SkijaControls.TEXT_FAINT, 8f);
            drawColorTrack(canvas, trackX, rowY, trackWidth, channel, hsv);
            float fraction = channelFraction(hsv, channel);
            SkijaControls.disc(canvas, trackX + trackWidth * fraction - 3.5f,
                    rowY + COLOR_SLIDER_HEIGHT * 0.5f - 3.5f, 7f, 0xFFFFFFFF);
            String shown = channel == 0 ? String.valueOf(Math.round(hsv[0]))
                    : String.valueOf(Math.round(hsv[channel] * 100f));
            SkijaUi.text(canvas, shown, trackX + trackWidth + 4f, rowY - 1f, COLOR_SLIDER_HEIGHT,
                    SkijaControls.TEXT_MUTED, 8f);
        }
    }

    /** 分量滑块的渐变轨道。 */
    private void drawColorTrack(Canvas canvas, float x, float y, float width, int channel,
                                float[] hsv) {
        float radius = COLOR_SLIDER_HEIGHT * 0.5f;
        if (channel == 0) {
            // 色相：六段纯色拼出一条完整彩虹
            float segment = width / 6f;
            for (int i = 0; i < 6; i++) {
                int from = fromHsv(i * 60f, 1f, 1f, 255);
                int to = fromHsv((i + 1) * 60f, 1f, 1f, 255);
                SkijaUi.gradient(canvas, x + segment * i, y, segment + 0.5f, COLOR_SLIDER_HEIGHT,
                        from, to, false, i == 0 || i == 5 ? radius : 0f);
            }
            return;
        }
        if (channel == 1) {
            SkijaUi.gradient(canvas, x, y, width, COLOR_SLIDER_HEIGHT,
                    fromHsv(hsv[0], 0f, hsv[2], 255), fromHsv(hsv[0], 1f, hsv[2], 255),
                    false, radius);
            return;
        }
        SkijaUi.gradient(canvas, x, y, width, COLOR_SLIDER_HEIGHT,
                fromHsv(hsv[0], hsv[1], 0f, 255), fromHsv(hsv[0], hsv[1], 1f, 255),
                false, radius);
    }

    /** 分量在 [0,1] 中的归一化位置（色相按 360 归一）。 */
    private static float channelFraction(float[] hsv, int channel) {
        if (channel == 0) {
            return Math.max(0f, Math.min(1f, hsv[0] / 360f));
        }
        return Math.max(0f, Math.min(1f, hsv[channel]));
    }

    /** 布尔胶囊：开=强调色填充+靠右的圆点，关=暗底+靠左的圆点。 */
    private void drawPill(Canvas canvas, SkijaControls.Box box, boolean on) {
        int fill = on ? SkijaTheme.accent() : SkijaControls.CARD;
        int stroke = on ? SkijaTheme.accent() : SkijaControls.STROKE_STRONG;
        SkijaUi.rounded(canvas, box.x, box.y, box.width, box.height, box.height * 0.5f, stroke);
        SkijaUi.rounded(canvas, box.x + 1f, box.y + 1f, box.width - 2f, box.height - 2f,
                (box.height - 2f) * 0.5f, fill);
        float knob = box.height - 6f;
        float knobX = on ? box.x + box.width - knob - 3f : box.x + 3f;
        SkijaControls.disc(canvas, knobX, box.y + 3f, knob, on ? 0xFF0B1113 : SkijaControls.TEXT);
    }

    /** 数值在 [min,max] 中的归一化位置（0–1）；区间退化时返回 0。 */
    private static float fractionOf(NumberValue value) {
        double min = value.min();
        double max = value.max();
        if (max <= min) {
            return 0f;
        }
        double fraction = (value.get().doubleValue() - min) / (max - min);
        return (float) Math.max(0d, Math.min(1d, fraction));
    }

    // ------------------------------------------------------------------ 颜色换算

    /** 0xAARRGGBB → {H(0–360), S(0–1), V(0–1)}。 */
    private static float[] toHsv(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue;
        if (delta == 0f) {
            hue = 0f;
        } else if (max == r) {
            hue = 60f * (((g - b) / delta) % 6f);
        } else if (max == g) {
            hue = 60f * (((b - r) / delta) + 2f);
        } else {
            hue = 60f * (((r - g) / delta) + 4f);
        }
        if (hue < 0f) {
            hue += 360f;
        }
        float saturation = max == 0f ? 0f : delta / max;
        return new float[]{hue, saturation, max};
    }

    /** {H,S,V} + alpha → 0xAARRGGBB。 */
    private static int fromHsv(float hue, float saturation, float value, int alpha) {
        float c = value * saturation;
        float hp = ((hue % 360f) + 360f) % 360f / 60f;
        float x = c * (1f - Math.abs(hp % 2f - 1f));
        float r = 0f;
        float g = 0f;
        float b = 0f;
        if (hp < 1f) {
            r = c;
            g = x;
        } else if (hp < 2f) {
            r = x;
            g = c;
        } else if (hp < 3f) {
            g = c;
            b = x;
        } else if (hp < 4f) {
            g = x;
            b = c;
        } else if (hp < 5f) {
            r = x;
            b = c;
        } else {
            r = c;
            b = x;
        }
        float m = value - c;
        int red = Math.round((r + m) * 255f);
        int green = Math.round((g + m) * 255f);
        int blue = Math.round((b + m) * 255f);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!open) {
            return false;
        }
        if (layout == null) {
            layout = currentLayout();
        }
        if (button == BUTTON_RIGHT) {
            // 右键：设置项恢复默认（不改选中状态，也不影响其它区域）
            return detailContains(mx, my) && detailRightClicked(mx, my);
        }
        if (button != 0) {
            return false;
        }
        // 标题栏的「编辑 HUD」按钮：这是"切界面"，不是"选分类"，所以在分类导航之前判断
        if (editHudButton().contains(mx, my)) {
            if (onEditHud != null) {
                onEditHud.run();
            }
            return true;
        }
        // 分类导航
        Category railHit = railAt(mx, my);
        if (railHit != null) {
            activeCategory = railHit;
            listScroll = 0f;
            return true;
        }
        // 模块列表：右侧命中区切启用，其余部分选中
        ModuleRowHit rowHit = moduleRowAt(mx, my);
        if (rowHit != null) {
            if (mx >= rowHit.right - MODULE_TOGGLE_HIT_WIDTH) {
                rowHit.module.toggle();
            } else {
                selectedModule = rowHit.module;
                detailScroll = 0f;
                expandedColor = null;
            }
            return true;
        }
        // 设置区：启用按钮、布尔胶囊、数值轨道、模式行、颜色块与调色板
        if (detailContains(mx, my)) {
            return detailClicked(mx, my);
        }
        return false;
    }

    /** 右栏内的左键：按与绘制相同的顺序重算热区，保证"点哪儿改哪儿"。 */
    private boolean detailClicked(double mx, double my) {
        Module module = selectedModule;
        if (module == null) {
            return false;
        }
        float x = layout.detailX() + 12f;
        float width = layout.detailWidth() - 24f;
        float y = layout.settingsY() + detailScroll + 42f;

        SkijaControls.Box enableBox = new SkijaControls.Box(x, y, width, 24f);
        if (enableBox.contains(mx, my)) {
            module.toggle();
            return true;
        }
        y += 30f;
        for (Value<?> value : module.values()) {
            if (value instanceof BooleanValue) {
                SkijaControls.Box pill = new SkijaControls.Box(x + width - PILL_WIDTH, y - 1f,
                        PILL_WIDTH, PILL_HEIGHT);
                if (pill.contains(mx, my)) {
                    ((BooleanValue) value).toggle();
                    return true;
                }
            } else if (value instanceof NumberValue) {
                if (withinRow(mx, my, x, y, width, 22f)) {
                    beginNumberDrag((NumberValue) value, x, width, mx);
                    return true;
                }
            } else if (value instanceof ModeValue) {
                if (withinRow(mx, my, x, y, width, 18f)) {
                    ((ModeValue) value).next();
                    return true;
                }
            } else if (value instanceof ColorValue) {
                ColorValue color = (ColorValue) value;
                SkijaControls.Box swatch = new SkijaControls.Box(x + width - SWATCH_WIDTH, y - 2f,
                        SWATCH_WIDTH, SWATCH_HEIGHT);
                if (swatch.contains(mx, my) || withinRow(mx, my, x, y, width - SWATCH_WIDTH - 6f, 18f)) {
                    expandedColor = color == expandedColor ? null : color;
                    if (expandedColor != null) {
                        // 展开后的行会变高：若因此超出可视区，自动滚动使其完整可见——否则用户
                        // 点开了调色板却看不到（也点不到）那三条滑块，看起来就像"点了没反应"。
                        ensureRowVisible(color);
                    }
                    return true;
                }
                if (color == expandedColor) {
                    int channel = colorChannelAt(mx, my, x, y + SETTING_ROW_HEIGHT - 6f, width);
                    if (channel >= 0) {
                        beginColorDrag(color, channel, mx);
                        return true;
                    }
                }
            }
            y += settingRowHeight(value);
        }
        return false;
    }

    /** 右栏内的右键：命中的设置项恢复默认值。 */
    private boolean detailRightClicked(double mx, double my) {
        Module module = selectedModule;
        if (module == null) {
            return false;
        }
        float x = layout.detailX() + 12f;
        float width = layout.detailWidth() - 24f;
        float y = layout.settingsY() + detailScroll + 42f + 30f;
        for (Value<?> value : module.values()) {
            if (withinRow(mx, my, x, y, width, settingRowHeight(value) - 4f)) {
                value.reset();
                return true;
            }
            y += settingRowHeight(value);
        }
        return false;
    }

    /** 指针是否落在某行的横向范围内。 */
    private static boolean withinRow(double mx, double my, float x, float y, float width,
                                     float height) {
        return mx >= x && mx <= x + width && my >= y - 2f && my <= y + height;
    }

    /**
     * 调整设置区滚动偏移，使某个设置项（含它展开后的完整高度）落在可视区内。
     *
     * <p>调用时机必须是「该项的行高已经变化之后」——即先设置展开状态、再调用本方法，
     * 否则算出来的高度是展开前的，滚动了还是会露不全。
     */
    private void ensureRowVisible(Value<?> target) {
        Module module = selectedModule;
        if (module == null) {
            return;
        }
        float offset = 0f;
        for (Value<?> value : module.values()) {
            if (value == target) {
                break;
            }
            offset += settingRowHeight(value);
        }
        // 详情内容顶端到第一个设置项的距离（模块名 22 + 分类 20 + 启用按钮 30）
        float rowTop = 72f + offset;
        float rowBottom = rowTop + settingRowHeight(target);
        float visible = layout.settingsHeight();
        float scroll = detailScroll;
        if (rowBottom + scroll > visible) {
            scroll = visible - rowBottom;
        }
        if (rowTop + scroll < 0f) {
            scroll = -rowTop;
        }
        detailScroll = clampScroll(scroll, detailContentHeight(), visible);
    }

    /** 颜色编辑器里命中的分量（0=H 1=S 2=V，-1 表示没命中）。 */
    private int colorChannelAt(double mx, double my, float x, float y, float width) {
        float labelWidth = 11f;
        float trackX = x + labelWidth;
        float trackWidth = Math.max(24f, width - labelWidth - 30f);
        if (mx < trackX - 4f || mx > trackX + trackWidth + 4f) {
            return -1;
        }
        for (int channel = 0; channel < 3; channel++) {
            float rowY = y + channel * (COLOR_SLIDER_HEIGHT + COLOR_SLIDER_GAP);
            if (my >= rowY - 2f && my <= rowY + COLOR_SLIDER_HEIGHT + 2f) {
                return channel;
            }
        }
        return -1;
    }

    private void beginNumberDrag(NumberValue value, float trackX, float trackWidth, double mx) {
        dragKind = DragKind.NUMBER;
        draggingNumber = value;
        draggingColor = null;
        draggingTrackX = trackX;
        draggingTrackWidth = trackWidth;
        applyDrag(mx);
    }

    private void beginColorDrag(ColorValue color, int channel, double mx) {
        dragKind = channel == 0 ? DragKind.COLOR_H : (channel == 1 ? DragKind.COLOR_S : DragKind.COLOR_V);
        draggingColor = color;
        draggingNumber = null;
        float labelWidth = 11f;
        draggingTrackX = layout.detailX() + 12f + labelWidth;
        draggingTrackWidth = Math.max(24f, layout.detailWidth() - 24f - labelWidth - 30f);
        applyDrag(mx);
    }

    /** 把指针位置换算成设置值并写入正在拖动的对象。 */
    private void applyDrag(double mx) {
        if (draggingTrackWidth <= 0f) {
            return;
        }
        double fraction = (mx - draggingTrackX) / draggingTrackWidth;
        fraction = Math.max(0d, Math.min(1d, fraction));
        if (dragKind == DragKind.NUMBER) {
            if (draggingNumber == null) {
                return;
            }
            double min = draggingNumber.min();
            double max = draggingNumber.max();
            draggingNumber.set(Double.valueOf(min + (max - min) * fraction));
            return;
        }
        if (draggingColor == null || dragKind == DragKind.NONE) {
            return;
        }
        float[] hsv = toHsv(draggingColor.argb());
        switch (dragKind) {
            case COLOR_H:
                hsv[0] = (float) (fraction * 360d);
                break;
            case COLOR_S:
                hsv[1] = (float) fraction;
                break;
            case COLOR_V:
                hsv[2] = (float) fraction;
                break;
            default:
                return;
        }
        int alpha = (draggingColor.argb() >>> 24) & 0xFF;
        draggingColor.set(Integer.valueOf(fromHsv(hsv[0], hsv[1], hsv[2], alpha)));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!open || dragKind == DragKind.NONE) {
            return false;
        }
        applyDrag(mx);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean wasDragging = dragKind != DragKind.NONE;
        dragKind = DragKind.NONE;
        draggingNumber = null;
        draggingColor = null;
        return open && wasDragging;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!open) {
            return false;
        }
        if (layout == null) {
            layout = currentLayout();
        }
        float step = (float) -amount * 18f;
        if (detailContains(mx, my)) {
            detailScroll = clampScroll(detailScroll + step, detailContentHeight(),
                    layout.settingsHeight());
            return true;
        }
        listScroll = clampScroll(listScroll + step, listContentHeight(), layout.moduleListHeight());
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        if (!open) {
            return false;
        }
        if (keyCode == KEY_ESCAPE) {
            setOpen(false);
            return true;
        }
        return false;
    }

    @Override
    public void cancelInteractions() {
        dragKind = DragKind.NONE;
        draggingNumber = null;
        draggingColor = null;
        expandedColor = null;
        detailScroll = 0f;
        listScroll = 0f;
    }

    // ------------------------------------------------------------------ 命中测试辅助

    private Category railAt(double mx, double my) {
        if (mx < layout.x() || mx > layout.x() + layout.railWidth()) {
            return null;
        }
        float x = layout.x() + (layout.railWidth() - RAIL_ITEM) * 0.5f;
        float y = layout.y() + layout.categoryTop();
        for (Category category : categories) {
            if (new SkijaControls.Box(x, y, RAIL_ITEM, RAIL_ITEM).contains(mx, my)) {
                return category;
            }
            y += RAIL_ITEM + RAIL_ITEM_TOP;
        }
        return null;
    }

    /** 命中的模块行：模块对象 + 该行的右边界（右端是启用开关的热区）。 */
    private ModuleRowHit moduleRowAt(double mx, double my) {
        if (mx < layout.moduleX() || mx > layout.moduleX() + layout.moduleWidth()
                || my < layout.bodyY() || my > layout.bodyY() + layout.bodyHeight()) {
            return null;
        }
        float rowY = layout.moduleListY() + listScroll;
        for (Module module : modulesOfActive()) {
            SkijaControls.Box row = new SkijaControls.Box(layout.moduleX() + 6f, rowY,
                    layout.moduleWidth() - 12f, MODULE_ROW_HEIGHT);
            if (row.contains(mx, my)) {
                return new ModuleRowHit(module, row.x + row.width);
            }
            rowY += MODULE_ROW_HEIGHT + MODULE_ROW_GAP;
        }
        return null;
    }

    private boolean detailContains(double mx, double my) {
        return mx >= layout.detailX() && mx <= layout.detailX() + layout.detailWidth()
                && my >= layout.bodyY() && my <= layout.bodyY() + layout.bodyHeight();
    }

    /** 模块列表的内容高度（用于滚动夹取）。 */
    private float listContentHeight() {
        return modulesOfActive().size() * (MODULE_ROW_HEIGHT + MODULE_ROW_GAP);
    }

    /** 设置区的内容高度（用于滚动夹取）；与绘制同一套行高计算。 */
    private float detailContentHeight() {
        Module module = selectedModule;
        if (module == null) {
            return 0f;
        }
        float height = 72f;
        for (Value<?> value : module.values()) {
            height += settingRowHeight(value);
        }
        return height;
    }

    /** 把滚动偏移夹取到 [可视高 - 内容高, 0]；内容不足时归零。 */
    private static float clampScroll(float offset, float contentHeight, float visibleHeight) {
        float limit = visibleHeight - contentHeight;
        if (limit >= 0f) {
            return 0f;
        }
        return Math.max(limit, Math.min(0f, offset));
    }

    /** 模块行命中结果：模块与所在行的右边界。 */
    private static final class ModuleRowHit {
        private final Module module;
        private final float right;

        private ModuleRowHit(Module module, float right) {
            this.module = module;
            this.right = right;
        }
    }
}
