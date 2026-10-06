package dev.nocturne.ui.hud;

import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;
import dev.nocturne.ui.theme.Theme;

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
     * <p>supplier 抛异常时返回空串（P13）：渲染帧不能被业务回调打掉整帧。
     *
     * @return supplier 提供的最新值；supplier 为 null、返回 null 或抛异常时为空串，绝不返回 null
     */
    public String currentText() {
        if (text == null) {
            return "";
        }
        try {
            String value = text.get();
            return value == null ? "" : value;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 绘制文本及可选阴影。
     *
     * <p>空文本直接跳过，避免产生无意义的绘制调用。
     * 阴影偏移按字号缩放（P13：固定 1px 在大字号下几乎看不见）；
     * 正文半透明时不画阴影——游戏字体路径会剥掉 alpha，不透明黑影比没影更丑。
     */
    @Override
    public void render(Renderer renderer) {
        String value = currentText();
        if (value.isEmpty()) {
            return;
        }
        if (shadow && color.a() == 255) {
            float offset = Math.max(1f, size / 12f);
            renderer.text(value, x + offset, y + offset, size, Theme.SHADOW);
        }
        renderer.text(value, x, y, size, color);
    }

    /**
     * @return 本元素在当前位置的包围盒 {x, y, width, height}（P13：外部需要尺寸做避让/对齐时用）；
     *         文本为空时宽高为 0
     */
    public float[] bounds(Renderer renderer) {
        String value = currentText();
        if (value.isEmpty()) {
            return new float[]{x, y, 0f, 0f};
        }
        return new float[]{x, y, renderer.textWidth(value, size), renderer.textHeight(size)};
    }
}
