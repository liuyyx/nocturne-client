package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.ColorValue;

/** 在真实准星方块中心绘制选择框。 */
public final class BlockOverlayModule extends GameModule implements WorldOverlay {
    private final ColorValue color = add(new ColorValue("Color", 0xFFFFFF55));

    public BlockOverlayModule() {
        super("BlockOverlay", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null || actions() == null) {
            return;
        }
        int[] pos = bridge.hitBlockPosition(actions().pick(5.0, 1.0f));
        if (pos == null) {
            return;
        }
        WorldProjection.ScreenPoint point = projection.project(pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5);
        if (point.visible) {
            draw.outline(point.x - 8f, point.y - 8f, 16f, 16f, 1.5f, color.argb());
        }
    }
}
