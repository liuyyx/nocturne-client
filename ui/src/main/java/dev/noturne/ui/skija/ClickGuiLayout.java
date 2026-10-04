/*
 * 移植自 Setsuna（上游 commit e4915ae093748d48d92ee47c38cdb8b2746a4730，作者 ShiYi，
 * 许可 GPL-3.0-or-later，见 THIRD-PARTY-NOTICES.md）。
 * 本文件相对上游的改动：record → Java 8 的 final 类（带访问器与 equals/hashCode/toString）；
 * 包名；可见性提升为 public（上游为包内可见）。
 */
package dev.noturne.ui.skija;

/**
 * ClickGUI 的响应式几何：三栏（分类导航 / 模块列表 / 设置详情）的全部坐标与尺寸都由这里推导。
 *
 * <p>绘制与命中测试共用**同一个**布局对象——这是"点哪儿亮哪儿"不出错的前提：如果两处各算一次，
 * 屏幕尺寸变化或缩放取整差异就会让热区与视觉错位。
 *
 * <p>尺寸规则（与上游一致）：面板占屏幕 56%×62%（按 GUI 缩放系数调整），居中；左栏是分类导航，
 * 中栏是模块列表，右栏是设置详情；右栏有最小宽度，窄屏时优先压缩中栏。
 */
public final class ClickGuiLayout {

    /** 面板离屏幕边缘的最小外边距（实际取值还会按屏幕短边收缩）。 */
    public static final float OUTER_MARGIN = 8.0F;

    private final float x;
    private final float y;
    private final float width;
    private final float height;
    private final float railWidth;
    private final float moduleWidth;

    private ClickGuiLayout(float x, float y, float width, float height,
                           float railWidth, float moduleWidth) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.railWidth = railWidth;
        this.moduleWidth = moduleWidth;
    }

    /**
     * 按屏幕尺寸与 GUI 缩放推导整屏布局。
     *
     * @param screenWidth  逻辑宽度（已是 GUI 缩放后的坐标）
     * @param screenHeight 逻辑高度
     * @param guiScale     用户的 GUI 缩放系数（仅用 0.65–1.25 区间）
     */
    public static ClickGuiLayout of(int screenWidth, int screenHeight, float guiScale) {
        float margin = Math.min(OUTER_MARGIN, Math.max(4.0F, Math.min(screenWidth, screenHeight) * 0.025F));
        float availableWidth = Math.max(1.0F, screenWidth - margin * 2.0F);
        float availableHeight = Math.max(1.0F, screenHeight - margin * 2.0F);
        float scale = clamp(guiScale, 0.65F, 1.25F);
        float width = clamp(screenWidth * 0.56F * scale,
                Math.min(screenWidth * 0.36F, availableWidth),
                Math.min(screenWidth * 0.78F, availableWidth));
        float height = clamp(screenHeight * 0.62F * scale,
                Math.min(screenHeight * 0.40F, availableHeight),
                Math.min(screenHeight * 0.84F, availableHeight));

        float railMinimum = Math.min(46.0F, width * 0.14F);
        float railMaximum = Math.min(56.0F, width);
        float moduleMinimum = Math.min(112.0F, width * 0.31F);
        float detailMinimum = Math.min(150.0F, width * 0.49F);
        float railWidth = clamp(width * 0.09F, railMinimum, railMaximum);
        float moduleWidth = clamp(width * 0.28F, moduleMinimum, Math.min(210.0F, width - railWidth));
        if (width - railWidth - moduleWidth < detailMinimum) {
            moduleWidth = Math.max(1.0F, width - railWidth - detailMinimum);
        }

        return new ClickGuiLayout(
                (screenWidth - width) * 0.5F,
                (screenHeight - height) * 0.5F,
                width,
                height,
                railWidth,
                moduleWidth);
    }

    /** 面板左边界。 */
    public float x() {
        return x;
    }

    /** 面板上边界。 */
    public float y() {
        return y;
    }

    /** 面板宽度。 */
    public float width() {
        return width;
    }

    /** 面板高度。 */
    public float height() {
        return height;
    }

    /** 左栏（分类导航）宽度。 */
    public float railWidth() {
        return railWidth;
    }

    /** 中栏（模块列表）宽度。 */
    public float moduleWidth() {
        return moduleWidth;
    }

    /** 中栏左边界。 */
    public float moduleX() {
        return x + railWidth;
    }

    /** 标题栏高度（按面板高度取，34–44 之间）。 */
    public float headerHeight() {
        return Math.min(height, clamp(height * 0.085F, 34.0F, 44.0F));
    }

    /** 内容区上边界（标题栏之下）。 */
    public float bodyY() {
        return y + headerHeight();
    }

    /** 内容区高度。 */
    public float bodyHeight() {
        return Math.max(0.0F, height - headerHeight());
    }

    /** 右栏（设置详情）左边界。 */
    public float detailX() {
        return moduleX() + moduleWidth;
    }

    /** 右栏宽度。 */
    public float detailWidth() {
        return width - railWidth - moduleWidth;
    }

    /** 模块列表上边界。 */
    public float moduleListY() {
        return y + moduleListOffset();
    }

    /** 模块列表可用高度。 */
    public float moduleListHeight() {
        return Math.max(0.0F, height - moduleListOffset());
    }

    /** 设置区上边界（详情头部之下）。 */
    public float settingsY() {
        return y + detailHeaderHeight();
    }

    /** 设置区可用高度。 */
    public float settingsHeight() {
        return Math.max(0.0F, height - detailHeaderHeight());
    }

    /** 分类导航首项的上边界（留出一段到标题栏的呼吸距离）。 */
    public float categoryTop() {
        return Math.min(height, headerHeight() + Math.min(16.0F, Math.max(7.0F, bodyHeight() * 0.06F)));
    }

    /** 内容区里为「模块」标题预留的高度；面板够高才留标题，否则省掉。 */
    public float moduleListOffset() {
        float titleSpace = bodyHeight() >= 54.0F ? 29.0F : 5.0F;
        return Math.min(height, headerHeight() + titleSpace);
    }

    /** 详情头部（标题 + 预览）底边界。 */
    public float detailHeaderHeight() {
        return Math.min(height, headerHeight() + inspectorHeaderHeight());
    }

    /** 详情预览区高度（占内容区 31%，夹在 68–88 之间）。 */
    public float inspectorHeaderHeight() {
        return Math.min(bodyHeight(), clamp(bodyHeight() * 0.31F, 68.0F, 88.0F));
    }

    /** 鼠标是否落在面板内。 */
    public boolean contains(double mouseX, double mouseY) {
        return contains(mouseX, mouseY, x, y, width, height);
    }

    /** 通用矩形命中测试（半开区间：左闭右开，避免相邻区域在边界上同时命中）。 */
    public static boolean contains(double mouseX, double mouseY,
                                   float x, float y, float width, float height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** 取值夹取。 */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 与上游 record 的语义保持一致：逐字段比较（float 用 {@link Float#compare}）。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ClickGuiLayout)) {
            return false;
        }
        ClickGuiLayout that = (ClickGuiLayout) other;
        return Float.compare(x, that.x) == 0
                && Float.compare(y, that.y) == 0
                && Float.compare(width, that.width) == 0
                && Float.compare(height, that.height) == 0
                && Float.compare(railWidth, that.railWidth) == 0
                && Float.compare(moduleWidth, that.moduleWidth) == 0;
    }

    /** 与 {@link #equals(Object)} 一致。 */
    @Override
    public int hashCode() {
        int result = Float.floatToIntBits(x);
        result = 31 * result + Float.floatToIntBits(y);
        result = 31 * result + Float.floatToIntBits(width);
        result = 31 * result + Float.floatToIntBits(height);
        result = 31 * result + Float.floatToIntBits(railWidth);
        result = 31 * result + Float.floatToIntBits(moduleWidth);
        return result;
    }

    /** 便于日志/测试排查布局取值。 */
    @Override
    public String toString() {
        return "ClickGuiLayout[x=" + x + ", y=" + y + ", width=" + width + ", height=" + height
                + ", railWidth=" + railWidth + ", moduleWidth=" + moduleWidth + "]";
    }
}
