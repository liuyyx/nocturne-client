package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

/** Crystal Chams：水晶缩放线框与独立填充/描边颜色。 */
public final class CrystalChamsModule extends GameModule implements WorldOverlay {
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0x46A078FF));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0xDCE6DCFF));
    private final NumberValue scale = add(new NumberValue("Scale", 1, 0.1, 3, 0.05));
    private final NumberValue lineWidth = add(new NumberValue("Line Width", 2, 0.5, 5, 0.5));

    public CrystalChamsModule() {
        super("Crystal Chams", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (!bridge().isInstance(entity, ClassType.END_CRYSTAL)) {
                return;
            }
            double[] pos = bridge().entityPosition(entity);
            if (pos == null) {
                return;
            }
            double size = scale.get();
            WorldGeometry.box(draw, projection, pos[0] - size * 0.5, pos[1], pos[2] - size * 0.5,
                    pos[0] + size * 0.5, pos[1] + size * 2.0, pos[2] + size * 0.5,
                    lineWidth.asFloat(), lineColor.argb());
            WorldProjection.ScreenPoint center = projection.project(pos[0], pos[1] + size, pos[2]);
            if (center.visible) {
                float radius = Math.max(2.0f, 100.0f / (float) Math.max(1.0, center.depth));
                draw.rect(center.x - radius, center.y - radius, radius * 2.0f, radius * 2.0f, sideColor.argb());
            }
        });
    }
}
