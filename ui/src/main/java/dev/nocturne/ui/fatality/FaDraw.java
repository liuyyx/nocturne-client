package dev.nocturne.ui.fatality;

/**
 * Fatality 界面的绘制词汇层：控件只认这一层，具体后端由 {@link BackendFaDraw} 提供。
 *
 * <p>坐标系是<b>设计单位</b>，原点在被叠加的绘制区左上角；{@link #translate}/{@link #scale}
 * 与 {@link #clip} 与上游语义一致（{@code push} 成对使用，恢复变换、透明度与裁剪）。
 */
abstract class FaDraw {

    /** 关闭时恢复变换、透明度与裁剪。 */
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * 绘制一个圆角矩形。
     *
     * @param radius 圆角半径；不大于 0.5 时退化为直角
     */
    abstract void rect(float x, float y, float w, float h, float radius, int argb);

    /** 绘制圆角描边。 */
    abstract void outline(float x, float y, float w, float h, float radius, float width, int argb);

    /** 四角渐变矩形：左上、右上、右下、左下。 */
    abstract void quad(float x, float y, float w, float h, int topLeft, int topRight, int bottomRight,
                       int bottomLeft);

    /** 高斯柔和阴影（矩形外扩 {@code sigma} 的衰减）。 */
    abstract void shadow(float x, float y, float w, float h, float radius, float sigma, int argb);

    /**
     * 绘制一行文本。
     *
     * @param y 文本盒上沿
     */
    abstract void text(String text, float x, float y, FaFont font, int argb);

    /** 玩家预览的姿态与 ESP 外观。{@code esp} 取 {@link #ESP_GLOW}/{@link #ESP_OUTLINE}；颜色为 ARGB。 */
    static final class PlayerView {
        /** 模型整体朝向（度）。 */
        final float yaw;
        /** 头部偏航（度）。 */
        final float headYaw;
        /** 头部俯仰（度）。 */
        final float headPitch;
        /** ESP 模式。 */
        final int esp;
        /** 主色。 */
        final int colorA;
        /** 渐变色（未开启渐变时等于主色）。 */
        final int colorB;
        /** 发光半径（设计单位）。 */
        final float glowRadius;

        PlayerView(float yaw, float headYaw, float headPitch, int esp, int colorA, int colorB,
                   float glowRadius) {
            this.yaw = yaw;
            this.headYaw = headYaw;
            this.headPitch = headPitch;
            this.esp = esp;
            this.colorA = colorA;
            this.colorB = colorB;
            this.glowRadius = glowRadius;
        }
    }

    /** 不做 ESP 处理。 */
    static final int ESP_NONE = 0;
    /** ESP 发光。 */
    static final int ESP_GLOW = 1;
    /** ESP 描边。 */
    static final int ESP_OUTLINE = 2;

    /**
     * 在给定盒子内绘制本地玩家模型（脚位于 {@code feetX, feetY}，高 {@code height}）。
     *
     * <p>默认实现返回 {@code false}：3D 预览由 {@code dev.nocturne.ui.preview.PlayerPreview} 单独接管，
     * 本词汇层不承担离屏渲染。返回 false 时调用方画静态占位内容。
     */
    boolean player(float boxX, float boxY, float boxW, float boxH, float feetX, float feetY,
                   float height, PlayerView view) {
        return false;
    }

    /**
     * 绘制一个物品图标。
     *
     * <p>默认空实现：本项目没有物品栏图标通路（26.x 的 {@code blit} 与 1.8.9 的 {@code glTexImage2D}
     * 都要额外的物品渲染管线），预览卡片降级为不画装备。
     */
    void item(Object stack, float x, float y, float size) {
    }

    /** 压入变换/透明度/裁剪状态，{@link Scope#close()} 时恢复。 */
    abstract Scope push();

    /** 平移（设计单位）。 */
    abstract void translate(float x, float y);

    /** 统一缩放。 */
    abstract void scale(float scale);

    /** 乘以不透明度（0..1）。 */
    abstract void opacity(float alpha);

    /** 裁剪到矩形（与当前裁剪求交），直到 scope 关闭。 */
    abstract Scope clip(float x, float y, float w, float h);

    /** 在本词汇层之外还有绘制时，先把待提交内容冲出去。 */
    void flush() {
    }

    // ── 便捷方法 ────────────────────────────────────────────────────────────

    final void rect(float x, float y, float w, float h, int argb) {
        rect(x, y, w, h, 0.0f, argb);
    }

    /** 上下两段渐变。 */
    final void vertical(float x, float y, float w, float h, int top, int bottom) {
        quad(x, y, w, h, top, top, bottom, bottom);
    }

    /** 左右两段渐变。 */
    final void horizontal(float x, float y, float w, float h, int left, int right) {
        quad(x, y, w, h, left, right, right, left);
    }

    /** 以 {@code centerY} 为垂直中心绘制文本。 */
    final void textMid(String text, float x, float centerY, FaFont font, int argb) {
        text(text, x, centerY - font.height() * 0.5f, font, argb);
    }

    /** 右对齐、垂直居中。 */
    final void textRightMid(String text, float right, float centerY, FaFont font, int argb) {
        // 先翻译再量宽：对齐必须基于**实际画出来的字**，否则右对齐会偏。
        String shown = FaStrings.t(text);
        textMid(shown, right - font.width(shown), centerY, font, argb);
    }

    /** 水平垂直居中。 */
    final void textCenter(String text, float centerX, float centerY, FaFont font, int argb) {
        String shown = FaStrings.t(text);
        textMid(shown, centerX - font.width(shown) * 0.5f, centerY, font, argb);
    }

    /** 以 (centerX, centerY) 为中心画一个图标字形。 */
    final void icon(String glyph, float centerX, float centerY, float size, int argb) {
        String shown = FaTheme.glyph(glyph);
        FaFont font = FaTheme.icon(size);
        text(shown, centerX - font.width(shown) * 0.5f, centerY - font.height() * 0.5f, font, argb);
    }
}
