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
            // 非正字号按 0 处理（D13）：否则负宽会让居中/省略号算出反向坐标。
            // §格式码不占宽（P17）：游戏内 "§aHi" 显示 2 字符，按 4 字符估算布局全错位。
            return text == null || size <= 0f ? 0f : stripCodes(text).length() * size * 0.5f;
        }

        @Override
        public float height(float size) {
            return size <= 0f ? 0f : size;
        }
    };

    /**
     * 剥掉 Minecraft 格式码（§ + 1 字符）：它们只改颜色/样式，不占显示宽度。
     */
    static String stripCodes(String text) {
        if (text == null || text.indexOf('§') < 0) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
