package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

/** 聊天界面叠加背景；不改原版聊天背景和文字透明度，也不提供模糊通道。 */
public final class BetterChatModule extends GameModule implements WorldOverlay {
    private final ColorValue background = add(new ColorValue("Background Color", 0x96101114));
    private final ColorValue inputBackground = add(new ColorValue("Input Background Color", 0xB9101114));
    private final NumberValue radius = add(new NumberValue("Chat Radius", 4.5, 0, 12, .5));
    private final NumberValue inputRadius = add(new NumberValue("Input Radius", 3.5, 0, 12, .5));
    private final BooleanValue shadow = add(new BooleanValue("Drop Shadow", true));
    private final ColorValue shadowColor = add(new ColorValue("Shadow Color", 0x6EFFFFFF));
    private long openedAt;
    private boolean chatOpen;

    public BetterChatModule() { super("Better Chat", Category.RENDER); }

    @Override protected void onStop() { chatOpen = false; openedAt = 0L; }

    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null || !bridge.isInstance(bridge.currentScreen(), ClassType.CHAT_SCREEN)) {
            chatOpen = false;
            return;
        }
        long now = System.nanoTime();
        if (!chatOpen) {
            chatOpen = true;
            openedAt = now;
        }
        if (draw.width() <= 4 || draw.height() <= 18) return;
        float t = Math.max(0f, Math.min(1f, (now - openedAt) / 180_000_000f));
        float remaining = 1f - t;
        float offset = draw.height() * .35f * remaining * remaining * remaining;
        float width = draw.width() - 4f;
        float height = draw.height() * .35f;
        float y = draw.height() - 18f - height + offset;
        float inputY = draw.height() - 18f + offset;
        if (shadow.get()) {
            draw.roundedRect(4f, y + 2f, width, height, radius.asFloat(), shadowColor.argb());
            draw.roundedRect(4f, inputY + 2f, width, 18f, inputRadius.asFloat(), shadowColor.argb());
        }
        draw.roundedRect(2f, y, width, height, radius.asFloat(), background.argb());
        draw.roundedRect(2f, inputY, width, 18f, inputRadius.asFloat(), inputBackground.argb());
    }
}
