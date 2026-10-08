package dev.nocturne.client.render;

/**
 * 世界覆盖层绘制面：模块用它画 2D 覆盖物（框、线、标签），**实现在 ui 侧**（挂在当前绘制后端上）。
 *
 * <p>为什么单独定一个接口而不是直接用 ui 的 {@code Renderer}：依赖方向是 {@code ui → client}，
 * client 侧看不到 ui 的类型。接口只收 {@code int argb} 与 {@code float}，不引入任何 ui 类型，
 * 于是同一份模块代码在四个后端（gl-fixed / gl-core / gui-extractor / skija）上都能画。
 *
 * <p>坐标是**屏幕逻辑像素**（与 {@link WorldProjection} 的输出、以及后端的
 * {@code width()/height()} 同一口径）。
 */
public interface OverlayDraw {

    /** 填充矩形（{@code x}/{@code y} 为左上角）。宽或高 ≤ 0、颜色全透明时实现应跳过。 */
    void rect(float x, float y, float width, float height, int argb);

    /** 矩形描边；{@code thickness} 为线宽（像素）。 */
    void outline(float x, float y, float width, float height, float thickness, int argb);

    /**
     * 两点连线。
     *
     * <p>默认实现沿线步进画小方块——绘制原语里没有旋转，这是不引入后端差异的做法。步长取
     * {@code max(1, thickness)}，因此线越粗、绘制次数越少。
     */
    default void line(float x1, float y1, float x2, float y2, float thickness, int argb) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length <= 0.01f) {
            return;
        }
        float t = Math.max(1f, thickness);
        int steps = Math.max(1, (int) (length / t));
        for (int i = 0; i <= steps; i++) {
            float f = (float) i / steps;
            rect(x1 + dx * f - t / 2f, y1 + dy * f - t / 2f, t, t, argb);
        }
    }

    /** 一行文本；字号由后端决定（各代际字体尺寸不同，实现自会取当前字体的原生行高）。 */
    void text(String text, float x, float y, int argb);

    /** @return 文本在当前字体下的绘制宽度（像素） */
    float textWidth(String text);

    /** @return 当前字体的行高（像素） */
    float textHeight();

    /** @return 绘制区宽（逻辑像素）；未知时为 0 */
    int width();

    /** @return 绘制区高（逻辑像素）；未知时为 0 */
    int height();
}
