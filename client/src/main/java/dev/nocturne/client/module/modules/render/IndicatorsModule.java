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
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.Locale;

/** 为屏外来袭投掷物显示方向、类型与距离。 */
public final class IndicatorsModule extends GameModule implements WorldOverlay {
    private final ModeValue alertType = add(new ModeValue("Alert Type", "Threat", "Always", "Threat", "Hit Only"));
    private final ColorValue uncommonColor = add(new ColorValue("Uncommon Projectile Color", 0xFFFF0000));
    private final BooleanValue showDistance = add(new BooleanValue("Show Distance", false));
    private final NumberValue radiusScale = add(new NumberValue("Radius Scale", 0.15, 0, 1, 0.05));
    private final BooleanValue arrows = add(new BooleanValue("Show Arrows", true));
    private final BooleanValue pearls = add(new BooleanValue("Show Pearls", false));
    private final BooleanValue potions = add(new BooleanValue("Show Potions", false));
    private final BooleanValue eggs = add(new BooleanValue("Show Eggs", true));
    private final BooleanValue snowballs = add(new BooleanValue("Show Snowballs", true));
    private final BooleanValue fireballs = add(new BooleanValue("Show Fireballs", true));

    public IndicatorsModule() {
        super("Indicators", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || !state.present()) {
            return;
        }
        RenderSupport.forEachEntity(bridge, entity -> {
            String kind = kind(bridge, entity);
            if (kind.isEmpty()) {
                return;
            }
            double distance = bridge.distance(state.player(), entity);
            if (alertType.is("Threat") && distance > (kind.equals("Arrow") ? 12.0 : 5.0)
                    || alertType.is("Hit Only") && distance > 1.5) {
                return;
            }
            double[] position = bridge.entityPosition(entity);
            WorldProjection.ScreenPoint point = projection.project(position[0], position[1], position[2]);
            if (RenderSupport.onScreen(point, draw)) {
                return;
            }
            float bearing = RotationController.wrap(RotationController.yawTo(
                    position[0] - state.x(), position[2] - state.z()) - state.yaw());
            int color = kind.equals("Fireball") ? 0xFFFF6D00 : uncommonColor.argb();
            RenderSupport.indicator(draw, bearing, radiusScale.asFloat(), color,
                    showDistance.get() ? kind + " " + String.format(Locale.ROOT, "%.0fm", distance) : kind);
        });
    }

    private String kind(GameBridge bridge, Object entity) {
        if (arrows.get() && bridge.isInstance(entity, ClassType.ABSTRACT_ARROW)) return "Arrow";
        if (pearls.get() && bridge.isInstance(entity, ClassType.THROWN_ENDER_PEARL)) return "Pearl";
        if (potions.get() && bridge.isInstance(entity, ClassType.THROWN_POTION)) return "Potion";
        if (eggs.get() && bridge.isInstance(entity, ClassType.EGG)) return "Egg";
        if (snowballs.get() && bridge.isInstance(entity, ClassType.SNOWBALL)) return "Snowball";
        if (fireballs.get() && bridge.isInstance(entity, ClassType.ABSTRACT_FIREBALL)) return "Fireball";
        return "";
    }
}
