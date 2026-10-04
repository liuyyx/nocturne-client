package dev.noturne.ui.hud;

import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.function.Supplier;

/**
 * 文本型读数元素：内容每帧从 {@link Supplier} 拉取，因此始终反映最新状态，调用方无需主动推送更新。
 */
public final class TextElement extends HudElement {

    /** 文本来源；每帧调用 {@code get()} 取值，为 null 时 {@link #currentText()} 返回空串 */
    private final Supplier<String> text;
    /** 文本颜色；构造时若传入 null 则回退为 {@link Theme#TEXT} */
    private final Color color;
    /** 字号，单位为渲染后端的文本尺度单位（本项目按像素处理） */
    private final float size;
    /** 是否绘制 1px 偏移的阴影副本，以在明亮背景上保持可读性 */
    private final boolean shadow;

    /**
     * 构造带阴影的文本元素。
     *
     * @param id 元素标识
     * @param text 文本来源，可为 null（视为空串）
     * @param size 字号
     * @param color 文本颜色，null 时使用 {@link Theme#TEXT}
     */
    public TextElement(String id, Supplier<String> text, float size, Color color) {
        this(id, text, size, color, true);
    }

    /**
     * 构造文本元素。
     *
     * @param id 元素标识
     * @param text 文本来源，可为 null（视为空串）
     * @param size 字号
     * @param color 文本颜色，null 时使用 {@link Theme#TEXT_PRIMARY}
     * @param shadow 是否绘制阴影副本
     */
    public TextElement(String id, Supplier<String> text, float size, Color color, boolean shadow) {
        super(id);
        this.text = text;
        this.size = size;
        this.color = color == null ? Theme.TEXT_PRIMARY : color;
        this.shadow = shadow;
    }

    /**
     * 读取当前文本。
     *
     * @return supplier 提供的最新值；supplier 为 null 或返回 null 时为空串，绝不返回 null
     */
    public String currentText() {
        String value = text == null ? null : text.get();
        return value == null ? "" : value;
    }

    /**
     * 绘制文本及可选阴影。
     *
     * <p>空文本直接跳过，避免产生无意义的绘制调用。
     */
    @Override
    public void render(Renderer renderer) {
        String value = currentText();
        if (value.isEmpty()) {
            return;
        }
        // 阴影先画且偏移 1px，正文后画覆盖其上形成描边感
        if (shadow) {
            renderer.text(value, x + 1f, y + 1f, size, Theme.SHADOW);
        }
        renderer.text(value, x, y, size, color);
    }
}
