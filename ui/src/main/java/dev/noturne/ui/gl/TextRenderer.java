package dev.noturne.ui.gl;

import dev.noturne.ui.render.Color;

/**
 * 文本绘制的抽象缝。
 *
 * <p>UI 模块从不链接游戏的字体渲染器；由客户端提供一个基于 Minecraft
 * {@code FontRenderer} 的实现。保持为接口，也让几何层可以在完全没有字体的情况下测试。
 */
public interface TextRenderer {

    /**
     * 绘制一行文本。
     *
     * @param text  文本内容
     * @param x     起始 x（屏幕坐标）
     * @param y     基线位置（屏幕坐标）
     * @param size  字号（像素）
     * @param color 文字颜色；实现应能处理 {@code null}
     */
    void draw(String text, float x, float y, float size, Color color);

    /** @return 文本在给定字号下的宽度（像素） */
    float width(String text, float size);

    /** @return 给定字号下的行高（像素） */
    float height(float size);

    /**
     * 空实现：什么都不画，宽度按固定步进估算；在真正的字体被定位到之前使用。
     * 之所以需要它，是为了让渲染器在字体绑定失败时仍可安全构造。
     */
    TextRenderer NONE = new TextRenderer() {
        @Override
        public void draw(String text, float x, float y, float size, Color color) {
        }

        @Override
        public float width(String text, float size) {
            return text == null ? 0f : text.length() * size * 0.5f;
        }

        @Override
        public float height(float size) {
            return size;
        }
    };
}
