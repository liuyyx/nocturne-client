package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;

/** 上游降级：仅叠加屏幕色调，不修改光照图或运行后处理着色器。 */
public final class FilterModule extends GameModule implements WorldOverlay {
    private final ModeValue mode = add(new ModeValue("Mode", "Shader", "Shader", "LightMap"));
    private final ColorValue color = add(new ColorValue("Base Color", 0x32464696));
    private boolean warned;

    public FilterModule() { super("Filter", Category.RENDER); }

    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!draw.reliableAlphaBlend()) {
            if (!warned) {
                warned = true;
                System.out.println("[nocturne] Filter: full-screen alpha blend unavailable on this backend");
            }
            return;
        }
        int tint = mode.is("LightMap") ? RenderSupport.withAlpha(color.argb(), .6f) : color.argb();
        draw.rect(0, 0, draw.width(), draw.height(), tint);
    }
}
