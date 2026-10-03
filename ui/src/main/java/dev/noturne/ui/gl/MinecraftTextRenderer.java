package dev.noturne.ui.gl;

import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.ui.render.Color;

/**
 * {@link TextRenderer} backed by the game's own font renderer.
 *
 * <p>On 1.8.9 that is {@code net.minecraft.client.gui.FontRenderer} (obfuscated {@code avn}),
 * reached through {@code Minecraft.fontRendererObj} ({@code ave.k}). Using the game's font keeps
 * the look native and needs no texture upload or glyph atlas of our own.
 */
public final class MinecraftTextRenderer implements TextRenderer {

    /** 1.8.9 draws at a fixed 9px line height; everything else is a scale factor. */
    private static final float BASE_HEIGHT = 9f;

    private final GameBridge bridge;
    private final Object fontRenderer;

    private MinecraftTextRenderer(GameBridge bridge, Object fontRenderer) {
        this.bridge = bridge;
        this.fontRenderer = fontRenderer;
    }

    /** Binds to the live font renderer, or returns {@code null} when the game is not reachable. */
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
        // Result is the advance width; ignored here (width() exposes it for layout).
        if (result == null) {
            // drawString overload not found: nothing sensible to fall back to
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

    private static float scale(float size) {
        return size <= 0f ? 1f : size / BASE_HEIGHT;
    }
}
