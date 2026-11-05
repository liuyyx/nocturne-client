package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

/** World Tweaks：雾范围/颜色与维度时钟修改。 */
public final class WorldTweaksModule extends GameModule implements WorldOverlay {
    private final BooleanValue fogModify = add(new BooleanValue("Fog Modify", true));
    private final NumberValue fogStart = add(new NumberValue("Fog Start", 0, 0, 256, 1));
    private final NumberValue fogEnd = add(new NumberValue("Fog End", 64, 10, 256, 1));
    private final ColorValue fogColor = add(new ColorValue("Fog Color", 0xFFA900FF));
    private final BooleanValue changeTime = add(new BooleanValue("Change Time", false));
    private final NumberValue time = add(new NumberValue("Time", 21, 0, 23, 1));

    public WorldTweaksModule() {
        super("World Tweaks", Category.RENDER);
    }

    @Override
    public void onTick() {
        if (changeTime.get()) {
            bridge().setDayTime(time.asInt() * 1000L);
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!fogModify.get()) {
            return;
        }
        float alpha = Math.min(0.35f, (fogEnd.asFloat() - fogStart.asFloat()) / 256.0f);
        int color = fogColor.argb() & 0xFFFFFF | (int) (255 * alpha) << 24;
        draw.rect(0, 0, draw.width(), draw.height(), color);
    }
}
