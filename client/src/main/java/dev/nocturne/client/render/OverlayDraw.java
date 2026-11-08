package dev.nocturne.client.render;

/**
 * 世界覆盖层绘制面：模块用它画 2D 覆盖物（框、线、标签），**实现在 ui 侧**（挂在当前绘制后端上）。
 *
 * <p>为什么单独定一个接口而不是直接用 ui 的 {@code Renderer}：依赖方向是 {@code ui → client}，
 * client 侧看不到 ui 的类型。接口只收 {@code int argb} 与 {@code float}，不引入任何 ui 类型，
 * 于是同一份模块代码在三个后端（gl-fixed / gl-core / gui-extractor）上都能画。
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

    /**
     * 圆角矩形（世界标签背景用）。
     *
     * @param x      左上角 x
     * @param y      左上角 y
     * @param width  宽
     * @param height 高
     * @param radius 圆角半径
     * @param argb   颜色
     */
    void roundedRect(float x, float y, float width, float height, float radius, int argb);

    /** @return 文本在当前字体下的绘制宽度（像素） */
    float textWidth(String text);

    /** @return 当前字体的行高（像素） */
    float textHeight();

    /**
     * 按指定字号画一行文本。
     *
     * <p>世界标签要按透视距离缩放（对齐 eps-plus 的 NameTags）：固定字号时远处标签和近处一样大，
     * 一屏几十个标签就糊成一片。后端的缩放能力是矩阵/位图字号，`size` 就是设计字号。
     *
     * @param text 文本
     * @param x    基线左端
     * @param y    基线顶端
     * @param size 字号（逻辑像素）
     * @param argb 颜色
     */
    void text(String text, float x, float y, float size, int argb);

    /**
     * @param text 文本
     * @param size 字号（逻辑像素）
     * @return 该字号下的绘制宽度
     */
    float textWidth(String text, float size);

    /**
     * @param size 字号（逻辑像素）
     * @return 该字号下的行高
     */
    float textHeight(float size);

    /** @return 绘制区宽（逻辑像素）；未知时为 0 */
    int width();

    /** @return 绘制区高（逻辑像素）；未知时为 0 */
    int height();

    /** 上传或更新一张 ARGB 纹理；返回可供 texturedQuad 使用的正句柄，失败时 ≤0。 */
    int uploadTexture(int id, int width, int height, int[] argb);

    /** 删除不再使用的纹理；无效句柄由后端忽略。 */
    void deleteTexture(int id);

    /** 绘制带 UV 与颜色调制的纹理矩形，供图标和世界 billboard 使用。 */
    void texturedQuad(int texture, float x, float y, float width, float height,
                      float u0, float v0, float u1, float v1, int argb);

    /** 当前后端是否支持纹理；false 时模块应退回文字/几何，不上传空纹理。 */
    boolean supportsTextures();

    /**
     * 本后端的 alpha 混合是否可靠到可以画**整帧遮罩**。
     *
     * <p>1.8.9–1.12 的固定管线（{@code GlRenderer}）上实测：同一个世界覆盖层在每帧内会被应用多次，
     * 25% 的雾色遮罩最终落成 ~44%，而雾色一旦接近黑（或 alpha 因状态失效被当成不透明），
     * 整个世界就是一块黑幕——只剩 HUD 与准星。因此这类后端必须显式返回 {@code false}，
     * 需要整帧混合的模块（雾色/后期滤镜）据此跳过自己，而不是把世界涂黑。
     *
     * @return true 表示可以安全地画半透明整帧遮罩；未知实现默认 true（不改变既有行为）
     */
    default boolean reliableAlphaBlend() {
        return true;
    }
}
