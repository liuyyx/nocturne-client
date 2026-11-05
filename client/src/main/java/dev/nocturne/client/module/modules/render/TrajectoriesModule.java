package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

/** Epsilon Trajectories：手持/他人/已发射弹的步进模拟、落点与位置盒。 */
public final class TrajectoriesModule extends GameModule implements WorldOverlay {
    private final TextValue items = add(new TextValue("Items", "bow,crossbow,trident,fishing_rod,snowball,egg,ender_pearl,experience_bottle,splash_potion,lingering_potion,wind_charge", 1024));
    private final BooleanValue otherPlayers = add(new BooleanValue("Other Players", true));
    private final BooleanValue firedProjectiles = add(new BooleanValue("Fired Projectiles", false));
    private final BooleanValue ignoreWitherSkulls = add(new BooleanValue("Ignore Wither Skulls", true));
    private final NumberValue simulationSteps = add(new NumberValue("Simulation Steps", 500, 0, 5000, 10));
    private final NumberValue ignoreFirstTicks = add(new NumberValue("Ignore First Ticks", 3, 0, 20, 1));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0xF5FF9600));
    private final NumberValue lineWidth = add(new NumberValue("Line Width", 2, 0.5, 5, 0.5));
    private final BooleanValue impactFill = add(new BooleanValue("Impact Fill", true));
    private final BooleanValue impactOutline = add(new BooleanValue("Impact Outline", true));
    private final ColorValue impactFillColor = add(new ColorValue("Impact Fill Color", 0x2DFF9600));
    private final ColorValue impactOutlineColor = add(new ColorValue("Impact Outline Color", 0xF5FFB437));
    private final BooleanValue renderPositionBoxes = add(new BooleanValue("Render Position Boxes", false));
    private final NumberValue positionBoxSize = add(new NumberValue("Position Box Size", 0.02, 0.005, 1, 0.005));
    private final ColorValue positionFillColor = add(new ColorValue("Position Fill Color", 0x2DFF9600));
    private final ColorValue positionOutlineColor = add(new ColorValue("Position Outline Color", 0xF5FF9600));

    public TrajectoriesModule() {
        super("Trajectories", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        RenderSupport.forEachEntity(bridge(), entity -> {
            boolean local = entity == bridge().player();
            if (bridge().isInstance(entity, ClassType.PLAYER) && (local || otherPlayers.get())) {
                Object stack = bridge().heldItem(entity, false);
                if (matches(stack)) {
                    double[] pos = bridge().entityPosition(entity);
                    Number yaw = number(bridge().readField(entity, ClassType.ENTITY, "yRot"));
                    Number pitch = number(bridge().readField(entity, ClassType.ENTITY, "xRot"));
                    if (pos != null && yaw != null && pitch != null) {
                        double[] angles = angles(yaw.floatValue(), pitch.floatValue());
                        simulate(draw, projection, new double[]{pos[0], pos[1] + 1.62, pos[2]},
                                new double[]{angles[0] * 1.5, angles[1] * 1.5, angles[2] * 1.5});
                    }
                }
            } else if (firedProjectiles.get() && projectile(entity)) {
                double[] pos = bridge().entityPosition(entity);
                double[] velocity = bridge().entityVelocity(entity);
                if (pos != null) {
                    simulate(draw, projection, pos, velocity);
                }
            }
        });
    }

    private void simulate(OverlayDraw draw, WorldProjection projection, double[] position, double[] velocity) {
        double x = position[0];
        double y = position[1];
        double z = position[2];
        double vx = velocity[0];
        double vy = velocity[1];
        double vz = velocity[2];
        WorldProjection.ScreenPoint previous = null;
        int steps = simulationSteps.asInt();
        for (int step = 0; step < steps; step++) {
            x += vx;
            y += vy;
            z += vz;
            vx *= 0.99;
            vy = vy * 0.99 - 0.03;
            vz *= 0.99;
            if (!bridge().isAir(bridge().blockStateAt((int) Math.floor(x), (int) Math.floor(y),
                    (int) Math.floor(z)))) {
                impact(draw, projection, x, y, z);
                break;
            }
            WorldProjection.ScreenPoint point = projection.project(x, y, z);
            if (previous != null && previous.visible && point.visible && step >= ignoreFirstTicks.asInt()) {
                draw.line(previous.x, previous.y, point.x, point.y, lineWidth.asFloat(), lineColor.argb());
            }
            if (renderPositionBoxes.get() && step % 5 == 0) {
                double size = positionBoxSize.get();
                WorldGeometry.box(draw, projection, x - size, y - size, z - size,
                        x + size, y + size, z + size, 0.5f, positionOutlineColor.argb());
                WorldGeometry.label(draw, projection, "■", x, y, z, positionFillColor.argb());
            }
            previous = point;
        }
    }

    private void impact(OverlayDraw draw, WorldProjection projection, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        int fill = impactFill.get() ? impactFillColor.argb() : 0;
        int outline = impactOutline.get() ? impactOutlineColor.argb() : 0;
        WorldGeometry.blockFilled(draw, projection, bx, by, bz, 1.0f, fill, outline);
    }

    private boolean matches(Object stack) {
        String id = bridge().itemDescription(stack).toLowerCase(java.util.Locale.ROOT);
        for (String item : items.get().toLowerCase(java.util.Locale.ROOT).split("[,;\\s]+")) {
            if (!item.isEmpty() && id.contains(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean projectile(Object entity) {
        String name = entity.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if (ignoreWitherSkulls.get() && name.contains("witherskull")) {
            return false;
        }
        return bridge().isInstance(entity, ClassType.SNOWBALL) || bridge().isInstance(entity, ClassType.EGG)
                || bridge().isInstance(entity, ClassType.THROWN_ENDER_PEARL)
                || bridge().isInstance(entity, ClassType.THROWN_POTION)
                || bridge().isInstance(entity, ClassType.ABSTRACT_ARROW)
                || bridge().isInstance(entity, ClassType.ABSTRACT_FIREBALL);
    }

    private static double[] angles(float yaw, float pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }
}
