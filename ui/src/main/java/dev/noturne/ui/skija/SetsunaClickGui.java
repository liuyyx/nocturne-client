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
 * 左键点设置区的开关/数值/模式控件直接改值（数值可按住拖动）；滚轮在模块列表与设置区分别滚动；
 * Esc 关闭（叠加层也会派发）。
 */
public final class SetsunaClickGui implements OverlayGui {

    /** Esc 键码（AWT VK_ESCAPE）。 */
    private static final int KEY_ESCAPE = 27;

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
    /** 设置区每行的高度。 */
    private static final float SETTING_ROW_HEIGHT = 34f;
    /** 数值滑块的轨道高度。 */
    private static final float TRACK_HEIGHT = 4f;
    /** 布尔开关胶囊的尺寸。 */
    private static final float PILL_WIDTH = 30f;
    private static final float PILL_HEIGHT = 16f;

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
    /** 正在拖动的数值设置（{@code null} 表示没有拖动）。 */
    private NumberValue dragging;
    /** 拖动开始时滑块轨道的左边界与宽度。 */
    private float draggingTrackX;
    private float draggingTrackWidth;

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
        int width = layout == null ? viewportWidth : Math.round(viewportWidth);
        int height = layout == null ? viewportHeight : Math.round(viewportHeight);
        // 背板：整屏压暗，让面板浮起来（上游独立屏幕同样是全屏背板）
        SkijaUi.fill(canvas, 0, 0, width, height, SkijaTheme.BACKDROP);
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

    /** 标题栏：品牌名（字距排版）+ 强调色圆点 + 右侧提示。 */
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

        String hint = "ESC 关闭";
        float hintWidth = SkijaUi.textWidth(hint, 8.5f);
        SkijaUi.text(canvas, hint, layout.x() + layout.width() - hintWidth - 12f, top,
                headerHeight, SkijaControls.TEXT_FAINT, 8.5f);
        // 标题栏底部一条分隔线：用弱描边的低透明，替代上游的整块 header 底色
        SkijaUi.fill(canvas, layout.x() + 1f, top + headerHeight, layout.width() - 2f, 1f,
                SkijaTheme.withAlpha(SkijaTheme.BORDER_SOFT, 90));
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
        List<Module> modules = activeCategory == null
                ? new ArrayList<Module>()
                : registry.byCategory(activeCategory);

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
     * <p>按值的运行时类型分派：布尔是胶囊开关、数值是轨道滑块、模式是左右循环、颜色是色块预览。
     * 用 {@code instanceof} 而不是引入访问者接口，是为了让值框架不认识 UI（见 {@code Value} 的注释）。
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
            String shown = number.display();
            float shownWidth = SkijaUi.textWidth(shown, 8.5f);
            SkijaUi.text(canvas, shown, x + width - shownWidth, y, 14f, SkijaControls.TEXT, 8.5f);
            float trackY = y + 16f;
            float trackWidth = width;
            float fraction = fractionOf(number);
            SkijaUi.rounded(canvas, x, trackY, trackWidth, TRACK_HEIGHT, TRACK_HEIGHT * 0.5f,
                    SkijaControls.CARD);
            SkijaUi.rounded(canvas, x, trackY, Math.max(TRACK_HEIGHT, trackWidth * fraction),
                    TRACK_HEIGHT, TRACK_HEIGHT * 0.5f, SkijaTheme.accent());
            SkijaControls.disc(canvas, x + trackWidth * fraction - 4f, trackY - 2.5f, 9f,
                    SkijaTheme.accent());
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
            SkijaUi.rounded(canvas, x + width - 34f, y - 1f, 18f, 14f, 3f, color.argb());
            String shown = color.display();
            float shownWidth = SkijaUi.textWidth(shown, 8f);
            SkijaUi.text(canvas, shown, x + width - 38f - shownWidth, y, 14f,
                    SkijaControls.TEXT_MUTED, 8f);
            return y + SETTING_ROW_HEIGHT;
        }
        // 未知值类型：只显示文本，不提供交互（新增值类型时在这里补控件）
        SkijaUi.text(canvas, value.display(), x + width - 40f, y, 14f,
                SkijaControls.TEXT_MUTED, 8.5f);
        return y + SETTING_ROW_HEIGHT;
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

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!open || button != 0) {
            return false;
        }
        if (layout == null) {
            layout = currentLayout();
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
            }
            return true;
        }
        // 设置区：启用按钮、布尔胶囊、数值轨道、模式行
        if (detailContains(mx, my)) {
            return detailClicked(mx, my);
        }
        return false;
    }

    /** 右栏内的点击：这里按与绘制相同的顺序重算热区，保证"点哪儿改哪儿"。 */
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
                if (mx >= x && mx <= x + width && my >= y - 2f && my <= y + 22f) {
                    NumberValue number = (NumberValue) value;
                    dragging = number;
                    draggingTrackX = x;
                    draggingTrackWidth = width;
                    applyDrag(mx);
                    return true;
                }
            } else if (value instanceof ModeValue) {
                if (mx >= x && mx <= x + width && my >= y - 2f && my <= y + 18f) {
                    ((ModeValue) value).next();
                    return true;
                }
            }
            y += SETTING_ROW_HEIGHT;
        }
        return false;
    }

    /** 把指针位置换算成数值并写入正在拖动的设置项。 */
    private void applyDrag(double mx) {
        if (dragging == null || draggingTrackWidth <= 0f) {
            return;
        }
        double fraction = (mx - draggingTrackX) / draggingTrackWidth;
        fraction = Math.max(0d, Math.min(1d, fraction));
        double min = dragging.min();
        double max = dragging.max();
        dragging.set(Double.valueOf(min + (max - min) * fraction));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!open || dragging == null) {
            return false;
        }
        applyDrag(mx);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean wasDragging = dragging != null;
        dragging = null;
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
            detailScroll = clampScroll(detailScroll + step, detailContentHeight(), layout.settingsHeight());
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
        dragging = null;
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

    /** 命中的模块行：模块对象 + 该行的左右边界（右端是启用开关的热区）。 */
    private ModuleRowHit moduleRowAt(double mx, double my) {
        if (mx < layout.moduleX() || mx > layout.moduleX() + layout.moduleWidth()
                || my < layout.bodyY() || my > layout.bodyY() + layout.bodyHeight()) {
            return null;
        }
        List<Module> modules = activeCategory == null
                ? new ArrayList<Module>()
                : registry.byCategory(activeCategory);
        float rowY = layout.moduleListY() + listScroll;
        for (Module module : modules) {
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
        if (activeCategory == null) {
            return 0f;
        }
        int count = registry.byCategory(activeCategory).size();
        return count * (MODULE_ROW_HEIGHT + MODULE_ROW_GAP);
    }

    /** 设置区的内容高度（用于滚动夹取）。 */
    private float detailContentHeight() {
        Module module = selectedModule;
        if (module == null) {
            return 0f;
        }
        return 72f + module.values().size() * SETTING_ROW_HEIGHT;
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
