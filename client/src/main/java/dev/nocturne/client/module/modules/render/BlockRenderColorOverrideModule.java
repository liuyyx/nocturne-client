package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.ColorValue;

/** 用配置颜色覆盖真实准星方块的选择标记。 */
public final class BlockRenderColorOverrideModule extends GameModule implements WorldOverlay {
    private final ColorValue color = add(new ColorValue("Block color", 0xFF40C8FF));

    public BlockRenderColorOverrideModule() {
        super("BlockRenderColorOverride", Category.RENDER);
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
        RenderSupport.marker(draw, projection, pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5, color.argb());
    }
}
