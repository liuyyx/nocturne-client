package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

/** Better Chat：聊天背景/输入框圆角、阴影与滑入动画。 */
public final class BetterChatModule extends GameModule implements WorldOverlay {
    private final ColorValue background = add(new ColorValue("Background Color", 0x96101114));
    private final ColorValue inputBackground = add(new ColorValue("Input Background Color", 0xB9101114));
    private final NumberValue chatRadius = add(new NumberValue("Chat Radius", 4.5, 0, 12, 0.5));
    private final NumberValue inputRadius = add(new NumberValue("Input Radius", 3.5, 0, 12, 0.5));
    private final NumberValue blurStrength = add(new NumberValue("Blur Strength", 8, 1, 20, 0.5));
    private final BooleanValue dropShadow = add(new BooleanValue("Drop Shadow", true));
    private final NumberValue shadowBlur = add(new NumberValue("Shadow Blur", 10, 2, 24, 1));
    private final ColorValue shadowColor = add(new ColorValue("Shadow Color", 0x6EFFFFFF));
    private long openedAt;

    public BetterChatModule() {
        super("Better Chat", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!bridge().isInstance(bridge().currentScreen(), ClassType.CHAT_SCREEN)) {
            openedAt = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (openedAt == 0L) {
            openedAt = now;
        }
        float progress = Math.min(1.0f, (now - openedAt) / 180.0f);
        progress = 1.0f - (float) Math.pow(1.0f - progress, 3);
        float panelHeight = draw.height() * 0.35f * progress;
        float y = draw.height() - 40.0f - panelHeight;
        if (dropShadow.get()) {
            float offset = shadowBlur.asFloat() * 0.25f;
            draw.roundedRect(2.0f + offset, y + offset, draw.width() - 4.0f, panelHeight,
                    chatRadius.asFloat(), shadowColor.argb());
        }
        int chatColor = withAlpha(background.argb(), progress);
        draw.roundedRect(2.0f, y, draw.width() - 4.0f, panelHeight, chatRadius.asFloat(), chatColor);
        float inputY = draw.height() - 24.0f;
        draw.roundedRect(4.0f, inputY, draw.width() - 8.0f, 18.0f, inputRadius.asFloat(),
                withAlpha(inputBackground.argb(), progress));
        if (blurStrength.get() > 0.0) {
            draw.outline(2.0f, y, draw.width() - 4.0f, panelHeight, 0.5f,
                    withAlpha(0xFFFFFFFF, Math.min(1.0f, blurStrength.asFloat() / 40.0f)));
        }
    }

    private static int withAlpha(int color, float factor) {
        return color & 0xFFFFFF | (int) ((color >>> 24) * factor) << 24;
    }
}
