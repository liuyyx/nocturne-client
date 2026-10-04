package dev.noturne.ui.render;

/**
 * UI 组件编写时所面向的绘制表面（抽象缝）。
 *
 * <p>实现有两类：游戏内的 OpenGL 后端（{@code GlRenderer} / {@code ModernRenderer}），
 * 以及测试用的无头记录器。把 UI 全部写在这层接口之上，
 * 就不需要 Minecraft 运行时也能跑通整棵组件树。
 */
public interface Renderer {


    /**
     * 绘制一个填充矩形。
     *
     * @param x      左上角 x（屏幕坐标）
     * @param y      左上角 y（屏幕坐标）
     * @param width  宽度；小于等于 0 时实现应跳过绘制
     * @param height 高度；小于等于 0 时实现应跳过绘制
     * @param color  填充色；为 {@code null} 时应跳过绘制
     */
    void rect(float x, float y, float width, float height, Color color);


    /**
     * 绘制一个圆角矩形。
     *
     * @param radius 圆角半径，实现应自行夹取到不超过宽高的一半；小于等于 0.5 时退化为普通矩形
     */
    void roundedRect(float x, float y, float width, float height, float radius, Color color);


    /** 绘制矩形描边；{@code lineWidth} 为线宽（像素）。 */
    void outline(float x, float y, float width, float height, float lineWidth, Color color);


    /**
     * 绘制一行文本。
     *
     * @param value 文本内容；为 {@code null} 或空串时应跳过绘制
     * @param size   字号（像素高度）
     */
    void text(String text, float x, float y, float size, Color color);


    /** @return 文本在给定字号下的绘制宽度（像素） */
    float textWidth(String text, float size);


    /** @return 给定字号下的行高（像素） */
    float textHeight(float size);

    /** 将随后的绘制裁剪到给定矩形，直到 {@link #popClip()}；支持嵌套。 */
    void pushClip(float x, float y, float width, float height);

    /** 弹出最近一次 {@link #pushClip} 设置的裁剪区域；栈空时为空操作。 */
    void popClip();
}
