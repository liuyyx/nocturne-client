package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.WorldChangeEvent;
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
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家落地后扩散的五秒圆环；上游贴图四边形降级成两层 48 段圆环与四段双色弧。
 * 动画使用构造期建立的 256 个槽，拥挤场景覆盖最旧槽，不逐帧创建粒子对象。
 */
public final class JumpCircleModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final ModeValue mode = add(new ModeValue("Mode", "Default", "Default", "Portal"));
    private final BooleanValue easeOut = add(new BooleanValue("Ease Out", true));
    private final NumberValue rotateSpeed = add(new NumberValue("Rotate Speed", 2, .5, 5, .1));
    private final NumberValue circleScale = add(new NumberValue("Circle Scale", 1, .5, 5, .1));
    private final BooleanValue onlySelf = add(new BooleanValue("Only Self", false));
    private final Map<UUID, GroundState> ground = new HashMap<UUID, GroundState>();
    private final Ring[] rings = new Ring[256];
    private final double[] position = new double[3];
    private GameBridge tickBridge;
    private int nextRing;
    private int tick;

    public JumpCircleModule() {
        super("Jump Circle", Category.RENDER);
        for (int i = 0; i < rings.length; i++) rings[i] = new Ring();
    }

    @Override
    protected void onStart() { listen(WorldChangeEvent.class, event -> clear()); }

    @Override
    protected void onStop() { clear(); }

    private void clear() {
        ground.clear();
        for (int i = 0; i < rings.length; i++) rings[i].born = 0L;
        nextRing = 0;
        tick = 0;
    }

    @Override
    public void onTick() {
        tickBridge = bridge();
        if (tickBridge == null) return;
        tick++;
        RenderSupport.forEachEntity(tickBridge, this);
        java.util.Iterator<GroundState> iterator = ground.values().iterator();
        while (iterator.hasNext()) if (tick - iterator.next().seen > 40) iterator.remove();
    }

    @Override
    public void visit(Object entity) {
        GameBridge bridge = tickBridge;
        if (!bridge.isInstance(entity, ClassType.PLAYER)
                || (onlySelf.get() && !RenderSupport.isSelf(bridge, entity))) return;
        UUID uuid = bridge.entityUuid(entity);
        Object value = bridge.readField(entity, ClassType.ENTITY, "onGround");
        if (uuid == null || !(value instanceof Boolean)) return;
        boolean current = (Boolean) value;
        GroundState state = ground.get(uuid);
        if (state == null) {
            state = new GroundState();
            state.ground = current;
            state.seen = tick;
            ground.put(uuid, state);
            return;
        }
        state.seen = tick;
        boolean previous = state.ground;
        state.ground = current;
        // 落地是 onGround 的 false→true；首个采样不制造一次虚假的落地。
        if (previous || !current || !bridge.entityPosition(entity, position)) return;
        Ring ring = rings[nextRing];
        nextRing = (nextRing + 1) % rings.length;
        ring.x = position[0]; ring.y = Math.floor(position[1]) + .001; ring.z = position[2];
        ring.born = System.nanoTime();
        ring.phase = (uuid.hashCode() & 255) / 255.0;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        long now = System.nanoTime();
        for (int i = 0; i < rings.length; i++) {
            Ring ring = rings[i];
            if (ring.born == 0L) continue;
            double seconds = (now - ring.born) / 1_000_000_000.0;
            double progress = seconds / 5.0;
            if (progress >= 1) { ring.born = 0L; continue; }
            double expansion = easeOut.get() ? 1 - Math.pow(1 - progress, 4) : progress;
            double radius = circleScale.get() * (.15 + expansion * 1.85);
            int first = mode.is("Portal") ? 0xFF9D55FF : 0xFF56E8FF;
            int second = mode.is("Portal") ? 0xFFFF7ED7 : 0xFFF2FFFF;
            for (int layer = 0; layer < 2; layer++) {
                double phase = (seconds * rotateSpeed.get() + ring.phase) * (layer == 0 ? 1 : -1);
                double layerRadius = radius * (layer == 0 ? 1 : .82);
                for (int segment = 0; segment < 48; segment++) {
                    double angle = segment * Math.PI * 2 / 48 + phase;
                    double next = (segment + 1) * Math.PI * 2 / 48 + phase;
                    float mix = (float) ((Math.sin((segment / 12) * Math.PI * .5 + phase) + 1) * .5);
                    int color = RenderSupport.withAlpha(RenderSupport.blend(first, second, mix),
                            (float) ((1 - progress) * (layer == 0 ? 1 : .65)));
                    WorldGeometry.line(draw, projection,
                            ring.x + Math.cos(angle) * layerRadius, ring.y, ring.z + Math.sin(angle) * layerRadius,
                            ring.x + Math.cos(next) * layerRadius, ring.y, ring.z + Math.sin(next) * layerRadius,
                            2f, color);
                }
            }
        }
    }

    private static final class GroundState {
        private boolean ground;
        private int seen;
    }

    private static final class Ring {
        private double x, y, z, phase;
        private long born;
    }
}
