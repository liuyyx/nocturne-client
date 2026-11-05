package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;

/** 模拟并绘制当前世界中投掷物的后续轨迹。 */
public final class ProjectilesModule extends GameModule implements WorldOverlay {
    private final BooleanValue arrows = add(new BooleanValue("Show Arrows", true));
    private final BooleanValue pearls = add(new BooleanValue("Show Pearls", true));
    private final BooleanValue potions = add(new BooleanValue("Show Potions", false));
    private final BooleanValue eggs = add(new BooleanValue("Show Eggs", false));
    private final BooleanValue snowballs = add(new BooleanValue("Show Snowballs", false));

    public ProjectilesModule() {
        super("Projectiles", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        RenderSupport.forEachEntity(bridge, entity -> {
            if (!wanted(bridge, entity)) {
                return;
            }
            double[] position = bridge.entityPosition(entity);
            Number mx = number(bridge.readField(entity, ClassType.ENTITY, "motionX"));
            Number my = number(bridge.readField(entity, ClassType.ENTITY, "motionY"));
            Number mz = number(bridge.readField(entity, ClassType.ENTITY, "motionZ"));
            if (position == null || mx == null || my == null || mz == null) {
                return;
            }
            double x = position[0];
            double y = position[1];
            double z = position[2];
            double vx = mx.doubleValue();
            double vy = my.doubleValue();
            double vz = mz.doubleValue();
            WorldProjection.ScreenPoint previous = projection.project(x, y, z);
            for (int step = 0; step < 40; step++) {
                x += vx;
                y += vy;
                z += vz;
                vx *= 0.99;
                vy = vy * 0.99 - 0.05;
                vz *= 0.99;
                WorldProjection.ScreenPoint next = projection.project(x, y, z);
                if (previous.visible && next.visible) {
                    draw.line(previous.x, previous.y, next.x, next.y, 1.5f, 0xFFFF4040);
                }
                previous = next;
            }
        });
    }

    private boolean wanted(GameBridge bridge, Object entity) {
        return arrows.get() && bridge.isInstance(entity, ClassType.ABSTRACT_ARROW)
                || pearls.get() && bridge.isInstance(entity, ClassType.THROWN_ENDER_PEARL)
                || potions.get() && bridge.isInstance(entity, ClassType.THROWN_POTION)
                || eggs.get() && bridge.isInstance(entity, ClassType.EGG)
                || snowballs.get() && bridge.isInstance(entity, ClassType.SNOWBALL);
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }
}
