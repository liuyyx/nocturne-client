package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.PacketEvent;
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
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 以投影几何代替上游粒子和贴图；不会替换原版图腾粒子或实体碰撞箱。
 * 爆炸包未区分风弹、重锤和其他爆炸，对应效果均为近似圆环；无法读取中心时不画。
 * Mace Spark、Mace Flash、Legacy Mace Shockwave、Shield Effect 和 Armor Particles 无对应钩子。
 */
public final class MasEffectsModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final BooleanValue wind = add(new BooleanValue("Wind Particles", true));
    private final BooleanValue mace = add(new BooleanValue("Mace Shockwave", true));
    private final NumberValue maceSize = add(new NumberValue("Mace Shockwave Size", 1, .1, 3, .05));
    private final NumberValue maceOpacity = add(new NumberValue("Mace Shockwave Opacity", 1, 0, 1, .05));
    private final BooleanValue totem = add(new BooleanValue("Custom Totem Effect", true));
    private final NumberValue totemOpacity = add(new NumberValue("Totem Effect Opacity", 1, 0, 1, .05));
    private final BooleanValue death = add(new BooleanValue("Player Death Effect", true));
    private final BooleanValue pearlTrail = add(new BooleanValue("Pearl Trail Particles", true));
    private final NumberValue pearlOpacity = add(new NumberValue("Pearl Trail Opacity", .5, 0, 1, .05));
    private final BooleanValue glide = add(new BooleanValue("Glide Icon", true));
    private final BooleanValue hitbox = add(new BooleanValue("Custom Hitbox", true));
    private final NumberValue mobOpacity = add(new NumberValue("Mob Hitbox Opacity", .3, 0, 1, .05));
    private final NumberValue playerOpacity = add(new NumberValue("Player Hitbox Opacity", 1, 0, 1, .05));
    private final NumberValue hitboxDistance = add(new NumberValue("Hitbox Fade Distance", 15, 0, 64, .5));
    private final NumberValue projectileDistance = add(new NumberValue("Projectile Fade Distance", 5, 0, 64, .5));
    private final ColorValue selfColor = add(new ColorValue("Self Pearl Color", 0xB3FFFF00));
    private final ColorValue allyColor = add(new ColorValue("Ally Pearl Color", 0xB30000FF));
    private final ColorValue otherColor = add(new ColorValue("Other Pearl Color", 0xB3FF0000));
    /** 网络线程只投递 id；实体与坐标只在 tick 线程读取。 */
    private final AtomicIntegerArray pending = new AtomicIntegerArray(32);
    private final AtomicReferenceArray<double[]> explosions = new AtomicReferenceArray<double[]>(32);
    private final double[][] effects = new double[32][5];
    private final int[] deadIds = new int[128];
    private final boolean[] deadSeen = new boolean[128];
    private final Trail[] trails = new Trail[64];
    private final Box[] boxes = new Box[512];
    private final double[] pos = new double[3];
    private final double[] playerPos = new double[3];
    private GameBridge tickBridge;
    private Object localPlayer;
    private final int[] tickEvents = new int[32];
    private Object world;
    private int effectNext, boxCount, tick;
    private boolean gliding, havePlayerPosition;

    public MasEffectsModule() {
        super("Mas Effects", Category.RENDER);
        for (int i = 0; i < deadIds.length; i++) deadIds[i] = -1;
        for (int i = 0; i < trails.length; i++) trails[i] = new Trail();
        for (int i = 0; i < boxes.length; i++) boxes[i] = new Box();
    }
    @Override protected void onStart() {
        listen(PacketEvent.class, this::packet);
        listen(WorldChangeEvent.class, event -> clear());
    }
    @Override protected void onStop() { clear(); }
    private void packet(PacketEvent event) {
        if (event.direction != PacketEvent.Direction.RECEIVE) return;
        GameBridge bridge = bridge();
        if (bridge == null) return;
        if (bridge.isInstance(event.packet, ClassType.CLIENTBOUND_EXPLOSION) && (wind.get() || mace.get())) {
            Object center = bridge.callMapped(event.packet, ClassType.CLIENTBOUND_EXPLOSION, "center");
            Object x = bridge.readField(center, ClassType.VEC3, "x");
            Object y = bridge.readField(center, ClassType.VEC3, "y");
            Object z = bridge.readField(center, ClassType.VEC3, "z");
            if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) {
                x = bridge.callMapped(event.packet, ClassType.CLIENTBOUND_EXPLOSION, "getX");
                y = bridge.callMapped(event.packet, ClassType.CLIENTBOUND_EXPLOSION, "getY");
                z = bridge.callMapped(event.packet, ClassType.CLIENTBOUND_EXPLOSION, "getZ");
            }
            if (x instanceof Number && y instanceof Number && z instanceof Number) {
                double[] point = {((Number) x).doubleValue(), ((Number) y).doubleValue(), ((Number) z).doubleValue()};
                for (int i = 0; i < explosions.length(); i++) if (explosions.compareAndSet(i, null, point)) break;
            }
            return;
        }
        if (!totem.get() || !bridge.isInstance(event.packet, ClassType.CLIENTBOUND_ENTITY_EVENT)) return;
        int[] data = bridge.entityEventData(event.packet);
        if (data == null || data.length < 2 || data[0] != 35 || data[1] < 0 || data[1] == Integer.MAX_VALUE) return;
        for (int i = 0; i < pending.length(); i++) if (pending.compareAndSet(i, 0, data[1] + 1)) break;
    }
    private void clear() {
        for (int i = 0; i < pending.length(); i++) pending.set(i, 0);
        for (int i = 0; i < explosions.length(); i++) explosions.set(i, null);
        for (int i = 0; i < effects.length; i++) effects[i][3] = 0;
        for (int i = 0; i < deadIds.length; i++) deadIds[i] = -1;
        for (Trail trail : trails) trail.id = -1;
        boxCount = 0; world = null; gliding = false;
    }
    @Override public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null || bridge.world() == null || bridge.player() == null) { clear(); return; }
        if (world != bridge.world()) { clear(); world = bridge.world(); }
        tick++;
        localPlayer = bridge.player(); tickBridge = bridge;
        havePlayerPosition = bridge.entityPosition(localPlayer, playerPos);
        gliding = glide.get() && Boolean.TRUE.equals(bridge.callMapped(localPlayer, ClassType.LIVING_ENTITY, "isFallFlying"));
        boxCount = 0;
        for (int i = 0; i < pending.length(); i++) tickEvents[i] = pending.getAndSet(i, 0);
        for (int i = 0; i < explosions.length(); i++) {
            double[] point = explosions.getAndSet(i, null);
            if (point != null) addEffect(point[0], point[1], point[2], 3);
        }
        for (int i = 0; i < deadSeen.length; i++) deadSeen[i] = false;
        try { RenderSupport.forEachEntity(bridge, this); } finally { tickBridge = null; localPlayer = null; }
        for (int i = 0; i < deadIds.length; i++) if (!deadSeen[i]) deadIds[i] = -1;
        for (Trail trail : trails) if (trail.id >= 0 && trail.seen != tick) trail.id = -1;
    }
    @Override public void visit(Object entity) {
        GameBridge bridge = tickBridge;
        boolean player = bridge.isInstance(entity, ClassType.PLAYER);
        boolean pearl = bridge.isInstance(entity, ClassType.THROWN_ENDER_PEARL);
        boolean projectile = pearl || bridge.isInstance(entity, ClassType.ABSTRACT_WIND_CHARGE);
        boolean living = player || bridge.isInstance(entity, ClassType.LIVING_ENTITY);
        if (!living && !projectile) return;
        if (!bridge.entityPosition(entity, pos)) return;
        int id = bridge.entityId(entity);
        if (id >= 0) {
            for (int i = 0; i < tickEvents.length; i++) if (tickEvents[i] == id + 1) { tickEvents[i] = 0; addEffect(pos[0], pos[1] + bridge.entityHeight(entity) * .5, pos[2], 1); }
            if (player) trackDeath(bridge, entity, id);
        }
        int color = 0xFFFFFFFF;
        if (pearl) {
            Object owner = bridge.callMapped(entity, ClassType.THROWABLE_PROJECTILE, "getOwner");
            color = owner == localPlayer ? selfColor.argb() : owner != null && bridge.isAllied(localPlayer, owner) ? allyColor.argb() : otherColor.argb();
            if (pearlTrail.get() && id >= 0) recordTrail(id, color);
        }
        if (!hitbox.get() || !havePlayerPosition || boxCount == boxes.length) return;
        double distance = Math.sqrt((pos[0] - playerPos[0]) * (pos[0] - playerPos[0]) + (pos[1] - playerPos[1]) * (pos[1] - playerPos[1]) + (pos[2] - playerPos[2]) * (pos[2] - playerPos[2]));
        double fadeDistance = projectile ? projectileDistance.get() : hitboxDistance.get();
        float fade = fadeDistance <= 0 ? 1f : (float) Math.max(0, 1 - distance / fadeDistance);
        float opacity = projectile ? 1f : (player ? playerOpacity.asFloat() : mobOpacity.asFloat());
        double width = bridge.entityWidth(entity), height = bridge.entityHeight(entity);
        if (width <= 0 || height <= 0 || fade <= 0) return;
        Box box = boxes[boxCount++]; box.x = pos[0]; box.y = pos[1]; box.z = pos[2]; box.radius = width * .5; box.height = height;
        box.color = RenderSupport.withAlpha(color, fade * opacity);
    }
    private void trackDeath(GameBridge bridge, Object entity, int id) {
        int slot = -1, empty = -1;
        for (int i = 0; i < deadIds.length; i++) { if (deadIds[i] == id) { slot = i; break; } if (deadIds[i] < 0 && empty < 0) empty = i; }
        boolean dead = bridge.isDeadForRendering(entity);
        if (slot < 0 && dead && death.get()) addEffect(pos[0], pos[1] + bridge.entityHeight(entity), pos[2], 2);
        if (dead) { if (slot < 0) slot = empty; if (slot >= 0) { deadIds[slot] = id; deadSeen[slot] = true; } }
    }
    private void addEffect(double x, double y, double z, int kind) {
        double[] e = effects[effectNext++ & (effects.length - 1)]; e[0] = x; e[1] = y; e[2] = z; e[3] = System.currentTimeMillis(); e[4] = kind;
    }
    private void recordTrail(int id, int color) {
        Trail chosen = null, free = null;
        for (Trail trail : trails) { if (trail.id == id) { chosen = trail; break; } if (trail.id < 0 && free == null) free = trail; }
        if (chosen == null) { chosen = free; if (chosen == null) return; chosen.id = id; chosen.count = 0; chosen.next = 0; }
        int at = chosen.next; chosen.points[at][0] = pos[0]; chosen.points[at][1] = pos[1]; chosen.points[at][2] = pos[2];
        chosen.next = (at + 1) % 16; chosen.count = Math.min(16, chosen.count + 1); chosen.color = color; chosen.seen = tick;
    }
    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (gliding && glide.get()) draw.text("⌁", draw.width() - 22, 8, 16f, 0xFFFFFFFF);
        if (hitbox.get()) for (int i = 0; i < boxCount; i++) { Box b = boxes[i]; WorldGeometry.box(draw, projection, b.x-b.radius, b.y, b.z-b.radius, b.x+b.radius, b.y+b.height, b.z+b.radius, 1f, b.color); }
        if (pearlTrail.get()) for (Trail trail : trails) if (trail.id >= 0) {
            int color = RenderSupport.withAlpha(trail.color, pearlOpacity.asFloat());
            for (int i = 1; i < trail.count; i++) { double[] a = trail.points[(trail.next - trail.count + i - 1 + 16) % 16], b = trail.points[(trail.next - trail.count + i + 16) % 16]; WorldGeometry.line(draw, projection, a[0], a[1], a[2], b[0], b[1], b[2], 1f, color); }
        }
        long now = System.currentTimeMillis();
        for (double[] e : effects) {
            if (e[3] == 0) continue; double t = Math.max(0, (now - e[3]) / 1000.0);
            if (e[4] == 1 && totem.get() && t < 1.5) {
                int color = RenderSupport.withAlpha(0xFFFFE77A, (float) ((1 - t / 1.5) * totemOpacity.get()));
                for (int j = 0; j < 8; j++) { double angle = j * Math.PI / 4 + t; WorldGeometry.label(draw, projection, "✦", e[0] + Math.cos(angle) * .5, e[1] + t, e[2] + Math.sin(angle) * .5, 10f, color); }
            } else if (e[4] == 2 && death.get() && t < 1.5) {
                int color = RenderSupport.withAlpha(0xFFFFFFFF, (float) (1 - t / 1.5));
                WorldGeometry.label(draw, projection, "☠", e[0], e[1] + t * .4, e[2], 14f, color);
                WorldGeometry.label(draw, projection, "✧", e[0] + .3, e[1] + t, e[2], 10f, color);
            } else if (e[4] == 3 && t < .6) {
                if (wind.get()) WorldGeometry.circle(draw, projection, e[0], e[1] + .05, e[2], t * 2, 24, 1f, RenderSupport.withAlpha(0xFFBBEFFF, (float) (1 - t / .6)));
                if (mace.get()) WorldGeometry.circle(draw, projection, e[0], e[1], e[2], t * 3 * maceSize.get(), 32, 2f, RenderSupport.withAlpha(0xFFFFFFFF, (float) ((1 - t / .6) * maceOpacity.get())));
            }
        }
    }
    private static final class Box { double x, y, z, radius, height; int color; }
    private static final class Trail { int id = -1, next, count, color, seen; final double[][] points = new double[16][3]; }
}
