package dev.noturne.ui.gl;

import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.ui.render.Color;

/**
 * 由游戏自带字体渲染器支撑的 {@link TextRenderer} 实现。
 *
 * <p>在 1.8.9 上就是 {@code net.minecraft.client.gui.FontRenderer}（混淆名 {@code avn}），
 * 通过 {@code Minecraft.fontRendererObj}（{@code ave.k}）取得。用游戏字体能保持原生观感，
 * 也无需自己上传纹理或维护字形图集。
 */
public final class MinecraftTextRenderer implements TextRenderer {

    /** 1.8.9 的原生行高为 9px；其他一切字号都由它换算得到。 */
    private static final float BASE_HEIGHT = 9f;

    /** 与游戏交互的桥，负责按映射名反射调用。 */
    private final GameBridge bridge;
    /** {@code FontRenderer} 实例，非 {@code null}。 */
    private final Object fontRenderer;

    /** 仅由 {@link #bind} 创建——必须先在游戏里定位到字体实例。 */
    private MinecraftTextRenderer(GameBridge bridge, Object fontRenderer) {
        this.bridge = bridge;
        this.fontRenderer = fontRenderer;
    }

    /**
     * 绑定到游戏正在使用的字体渲染器。
     *
     * @param bridge 与游戏的桥
     * @return 绑定结果；游戏不可达、映射缺失或任何反射异常时返回 {@code null}
     */
    public static MinecraftTextRenderer bind(GameBridge bridge) {
        if (bridge == null) {
            return null;
        }
        try {
            Object minecraft = bridge.minecraft();
            if (minecraft == null) {
                return null;
            }
            Object font = bridge.readField(minecraft, ClassType.MINECRAFT, "fontRenderer");
            return font == null ? null : new MinecraftTextRenderer(bridge, font);
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return 底层的 {@code FontRenderer} 实例，供诊断使用 */
    public Object fontRenderer() {
        return fontRenderer;
    }

    @Override
    public void draw(String text, float x, float y, float size, Color color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int rgb = color == null ? 0xFFFFFF : (color.argb & 0xFFFFFF);
        Object result = bridge.callMapped(fontRenderer, ClassType.FONT_RENDERER, "drawString",
                text, (int) x, (int) y, rgb);
        // 返回值是文本的推进宽度，但这里用不上（布局请走 width()）。
        if (result == null) {
            // 没找到 drawString 的对应重载：没有任何合理的回退方式，静默忽略。
        }
    }

    @Override
    public float width(String text, float size) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        Object result = bridge.callMapped(fontRenderer, ClassType.FONT_RENDERER, "getStringWidth", text);
        float base = result instanceof Number ? ((Number) result).floatValue() : text.length() * 6f;
        return base * scale(size);
    }

    @Override
    public float height(float size) {
        return size;
    }

    /**
     * 把请求字号换算为字体缩放系数。
     *
     * @param size 期望的字号（像素）
     * @return 缩放系数；size 非正时按 1 处理，避免除零或反向缩放
     */
    private static float scale(float size) {
        return size <= 0f ? 1f : size / BASE_HEIGHT;
    }
}
