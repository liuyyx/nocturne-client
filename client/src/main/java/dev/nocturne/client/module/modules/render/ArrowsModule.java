package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
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

import java.util.Locale;

/** 在屏幕椭圆环上标记屏外敌人方向。 */
public final class ArrowsModule extends GameModule implements WorldOverlay {
    private final ColorValue color = add(new ColorValue("Color", 0xFFFF0000));
    private final NumberValue radiusScale = add(new NumberValue("Radius Scale", 0.5, 0, 1, 0.05));
    private final BooleanValue showDistance = add(new BooleanValue("Show Distance", false));
    private final BooleanValue scaleOpacity = add(new BooleanValue("Scale Opacity", false));

    public ArrowsModule() {
        super("Arrows", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || !state.present()) {
            return;
        }
        RenderSupport.forEachEntity(bridge, entity -> {
            if (entity == state.player() || !bridge.isInstance(entity, ClassType.PLAYER)
                    || bridge.isAllied(state.player(), entity)) {
                return;
            }
            double[] position = bridge.entityPosition(entity);
            if (position == null) {
                return;
            }
            WorldProjection.ScreenPoint point = projection.project(position[0], position[1] + 1.0, position[2]);
            if (RenderSupport.onScreen(point, draw)) {
                return;
            }
            float bearing = RotationController.wrap(RotationController.yawTo(
                    position[0] - state.x(), position[2] - state.z()) - state.yaw());
            double distance = bridge.distance(state.player(), entity);
            int argb = color.argb();
            if (scaleOpacity.get()) {
                int alpha = Math.max(20, Math.min(255, (int) (255 - distance * 1.5)));
                argb = argb & 0x00FFFFFF | alpha << 24;
            }
            RenderSupport.indicator(draw, bearing, radiusScale.asFloat(), argb,
                    showDistance.get() ? String.format(Locale.ROOT, "%.0fm", distance) : "");
        });
    }
}
