package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

import java.util.List;

/** Better Scoreboard：圆角背景、偏移、阴影和自定义模糊强度。 */
public final class ScoreboardModule extends GameModule implements WorldOverlay {
    private final ColorValue background = add(new ColorValue("Background Color", 0x5A101114));
    private final NumberValue radius = add(new NumberValue("Radius", 4.5, 0, 12, 0.5));
    private final NumberValue xOffset = add(new NumberValue("X Offset", 0, -500, 500, 1));
    private final NumberValue yOffset = add(new NumberValue("Y Offset", 0, -500, 500, 1));
    private final BooleanValue backgroundBlur = add(new BooleanValue("Background Blur", true));
    private final NumberValue blurStrength = add(new NumberValue("Blur Strength", 8, 1, 20, 0.5));
    private final BooleanValue drawShadow = add(new BooleanValue("Draw Shadow", true));
    private final NumberValue shadowBlur = add(new NumberValue("Shadow Blur", 10, 2, 24, 1));
    private final ColorValue shadowColor = add(new ColorValue("Shadow Color", 0x6EFFFFFF));

    public ScoreboardModule() {
        super("Better Scoreboard", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        List<String> lines = bridge().scoreboardLines();
        if (lines.isEmpty()) {
            return;
        }
        float width = 0.0f;
        for (String line : lines) {
            width = Math.max(width, draw.textWidth(line));
        }
        float height = lines.size() * draw.textHeight() + 6.0f;
        float x = draw.width() - width - 8.0f + xOffset.asFloat();
        float y = draw.height() * 0.35f + yOffset.asFloat();
        if (drawShadow.get()) {
            float offset = shadowBlur.asFloat() * 0.25f;
            draw.roundedRect(x - 4.0f + offset, y - 3.0f + offset, width + 8.0f, height,
                    radius.asFloat(), shadowColor.argb());
        }
        int back = background.argb();
        if (backgroundBlur.get()) {
            back = back & 0xFFFFFF | Math.min(back >>> 24, blurStrength.asInt() * 8) << 24;
        }
        draw.roundedRect(x - 4.0f, y - 3.0f, width + 8.0f, height, radius.asFloat(), back);
        for (String line : lines) {
            draw.text(line, x, y, 0xFFFFFFFF);
            y += draw.textHeight();
        }
    }
}
