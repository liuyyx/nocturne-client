package dev.nocturne.ui.fatality;

import dev.nocturne.client.value.Value;

import java.util.Locale;

/**
 * 卡片里的一行：左边标签，右边控件。行会记住上一帧绘制的位置，因此两帧之间的输入命中
 * 的正是屏幕上看到的东西。
 */
abstract class FaRow {
    /** 本行绑定的设置项；仅用于可见性判断，已启用的行间开关（Enabled）为 {@code null}。 */
    protected final Value<?> value;
    /** 显示标签。 */
    protected final String label;
    /** 可见性动画（0..1），配合高度动画让出现/消失不突兀。 */
    private final FaMotion visibility;
    /** 悬停动画。 */
    protected final FaMotion hover = new FaMotion(0.0f);
    /** 上一帧的绘制位置（设计单位）。 */
    protected float x;
    /** 上一帧的绘制位置（设计单位）。 */
    protected float y;
    /** 上一帧的绘制宽度（设计单位）。 */
    protected float w;
    /** 上一帧是否真的画出来（被滚动裁掉时为 false），命中测试据此短路。 */
    protected boolean shown;
    /** 父组附加的可见性条件；顶层行为 {@code null}。 */
    private java.util.function.BooleanSupplier parentVisibility;

    protected FaRow(Value<?> value, String label) {
        this.value = value;
        this.label = label;
        this.visibility = new FaMotion(visible() ? 1.0f : 0.0f);
    }

    /** @return 本行当前是否应显示；设置项不存在时只看父组条件 */
    boolean visible() {
        return (value == null || value.visible())
                && (parentVisibility == null || parentVisibility.getAsBoolean());
    }

    /** 绑定父组条件，供 GroupValue 的子行使用。 */
    void parentVisibility(java.util.function.BooleanSupplier condition) {
        parentVisibility = condition;
        visibility.snap(visible() ? 1.0f : 0.0f);
    }

    /** @return 稳定高度（设计单位），用于列高平衡 */
    float targetHeight() {
        return visible() ? FaTheme.ROW_H : 0.0f;
    }

    /** @return 动画高度（设计单位），用于绘制 */
    float height(float dt) {
        return visibility.toward(visible() ? 1.0f : 0.0f, dt, 18.0f) * FaTheme.ROW_H;
    }

    /** @return 可见性动画值（0..1） */
    float visibility() {
        return visibility.value();
    }

    /** 记录位置并绘制；{@code alpha} 为父级不透明度。 */
    final void render(FaContext ctx, float x, float y, float w, float alpha) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.shown = alpha > 0.5f && visibility.value() > 0.9f;
        boolean hovered = ctx.hovered(x, y, w, FaTheme.ROW_H);
        hover.toward(hovered ? 1.0f : 0.0f, ctx.dt, 16.0f);
        draw(ctx, x, y + FaTheme.ROW_H * 0.5f, w, alpha);
    }

    /**
     * 绘制本行。
     *
     * @param centerY 行垂直中心
     */
    protected abstract void draw(FaContext ctx, float x, float centerY, float w, float alpha);

    /** 画标签（左对齐、垂直居中，超宽截断加省略号）。 */
    protected void drawLabel(FaContext ctx, float x, float centerY, float maxWidth, float alpha) {
        FaFont font = FaTheme.label();
        int color = FaTheme.mix(FaTheme.LABEL, FaTheme.LABEL_HOVER, hover.value());
        ctx.draw.textMid(FaTheme.fit(FaStrings.t(label), font, maxWidth), x + FaTheme.ROW_PAD,
                centerY, font, FaTheme.alpha(color, alpha));
    }

    /** @return 行右内边距处的 x */
    protected float right() {
        return x + w - FaTheme.ROW_PAD;
    }

    /** @return 行垂直中心 */
    protected float centerY() {
        return y + FaTheme.ROW_H * 0.5f;
    }

    /** @return 指针是否命中本行（且本行确实画出来了） */
    protected boolean hit(double mx, double my) {
        return shown && FaContext.inside(mx, my, x, y, w, FaTheme.ROW_H);
    }

    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        return false;
    }

    void mouseDragged(FaContext ctx, double mx, double my) {
    }

    void mouseReleased() {
    }

    boolean mouseScrolled(FaContext ctx, double mx, double my, double amount) {
        return false;
    }

    /** @return 标签或控件内容是否匹配搜索词（{@code query} 已小写） */
    boolean matches(String query) {
        return label.toLowerCase(Locale.ROOT).contains(query);
    }

    /** 右侧深色输入框左边缘。 */
    protected float fieldX(float width) {
        return right() - width;
    }

    /** 右侧输入框上沿。 */
    protected float fieldY() {
        return centerY() - FaTheme.FIELD_H * 0.5f;
    }

    /** 滑条轨道上沿（比输入框矮，但命中区域仍按输入框算）。 */
    protected float sliderY() {
        return centerY() - FaTheme.SLIDER_H * 0.5f;
    }

    /** 数值文本：步长不小于 1 且取整时输出整数，否则保留两位并去掉尾随 0。 */
    protected static String formatNumber(double value, double increment) {
        if (increment >= 1.0 && value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
