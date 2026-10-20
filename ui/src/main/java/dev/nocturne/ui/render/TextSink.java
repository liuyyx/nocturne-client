package dev.nocturne.ui.render;

/**
 * 文本绘制与度量的最小接口，供 {@link DrawPlan} 委托。
 *
 * <p>文字不进绘制计划：字形位置依赖具体字体（游戏字体、栅格化字体、26.x 的固定行高字体），
 * 由各版本的后端在 {@code DrawSink} 之外处理；这里的接口只保证组件树拿到一致的宽度/行高。
 */
public interface TextSink {

    /** 绘制一行文本；实现应能处理 {@code null} 文本与 {@code null} 颜色。 */
    void draw(String text, float x, float y, float size, Color color);

    /** @return 文本在给定字号下的宽度（像素） */
    float width(String text, float size);

    /** @return 给定字号下的行高（像素） */
    float height(float size);
}
