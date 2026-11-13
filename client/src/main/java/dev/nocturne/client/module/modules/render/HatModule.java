package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldGeometry;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/**
 * 玩家锥帽的圆环与母线近似；不提交三角面。
 * Only Third Person 按上游只限制本地玩家，其他玩家的帽子不受本地第一人称视角影响。
 */
public final class HatModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private static final int[] ASTOLFO = {0xFFFFB7D5, 0xFFFFA9FA, 0xFFC8B8FF,
            0xFF9EE9FF, 0xFFB5FFF1, 0xFFFFD4E5};
    private final ModeValue mode = add(new ModeValue("Mode", "Astolfo", "Astolfo", "Sexy", "Fade"));
    private final NumberValue points = add(new NumberValue("Points", 30, 3, 180, 1));
    private final NumberValue size = add(new NumberValue("Size", .5, .1, 3, .1));
    private final NumberValue offset = add(new NumberValue("Offset", 2000, 0, 5000, 100));
    private final ColorValue color = add(new ColorValue("Color", 0xFFFFFFFF));
    private final ColorValue secondColor = add(new ColorValue("Second Color", 0xFF000000));
    private final BooleanValue thirdPerson = add(new BooleanValue("Only Third Person", true));
    private final double[] ringX = new double[181];
    private final double[] ringZ = new double[181];
    private final double[] position = new double[3];
    private int cachedPoints;
    private double cachedRadius = -1;
    private long ticks;
    private GameBridge frameBridge;
    private OverlayDraw frameDraw;
    private WorldProjection frameProjection;

    public HatModule() { super("Hat", Category.RENDER); }

    @Override
    public void onTick() { ticks++; }

    private void updateRing() {
        int count = points.asInt();
        double radius = size.get();
        if (count == cachedPoints && radius == cachedRadius) return;
        cachedPoints = count; cachedRadius = radius;
        for (int i = 0; i <= count; i++) {
            double angle = i * Math.PI * 2 / count;
            ringX[i] = Math.cos(angle) * radius;
            ringZ[i] = Math.sin(angle) * radius;
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        frameBridge = bridge();
        if (frameBridge == null) return;
        updateRing();
        frameDraw = draw; frameProjection = projection;
        RenderSupport.forEachEntity(frameBridge, this);
    }

    @Override
    public void visit(Object entity) {
        GameBridge bridge = frameBridge;
        if (!bridge.isInstance(entity, ClassType.PLAYER)
                || (thirdPerson.get() && RenderSupport.isSelf(bridge, entity) && bridge.perspective() == 0)
                || !bridge.entityPosition(entity, position)) return;
        Object yawValue = bridge.readField(entity, ClassType.ENTITY, "yRot");
        double yaw = Math.toRadians(yawValue instanceof Number ? ((Number) yawValue).doubleValue() : 0);
        double cosine = Math.cos(yaw), sine = Math.sin(yaw);
        double tip = position[1] + bridge.entityHeight(entity) - .15;
        if (Boolean.TRUE.equals(bridge.callMapped(entity, ClassType.ENTITY, "isCrouching"))) tip -= .2;
        double base = tip - cachedRadius * .5;
        double phase = ticks * .01 + offset.get() / 1000.0;
        for (int i = 0; i < cachedPoints; i++) {
            double x = position[0] + ringX[i] * cosine - ringZ[i] * sine;
            double z = position[2] + ringX[i] * sine + ringZ[i] * cosine;
            double nextX = position[0] + ringX[i + 1] * cosine - ringZ[i + 1] * sine;
            double nextZ = position[2] + ringX[i + 1] * sine + ringZ[i + 1] * cosine;
            int tint;
            if (mode.is("Astolfo")) {
                double hue = (phase + (double) i / cachedPoints) * ASTOLFO.length;
                int index = Math.floorMod((int) Math.floor(hue), ASTOLFO.length);
                tint = RenderSupport.blend(ASTOLFO[index], ASTOLFO[(index + 1) % ASTOLFO.length],
                        (float) (hue - Math.floor(hue)));
            } else if (mode.is("Sexy")) {
                tint = ((i + (int) (phase * cachedPoints)) & 1) == 0 ? color.argb() : secondColor.argb();
            } else {
                tint = RenderSupport.blend(color.argb(), secondColor.argb(),
                        (float) ((Math.sin(phase * Math.PI * 2 + i * Math.PI * 2 / cachedPoints) + 1) * .5));
            }
            WorldGeometry.line(frameDraw, frameProjection, x, base, z, nextX, base, nextZ, 1f, tint);
            WorldGeometry.line(frameDraw, frameProjection, position[0], tip, position[2], x, base, z, 1f, tint);
        }
    }
}
