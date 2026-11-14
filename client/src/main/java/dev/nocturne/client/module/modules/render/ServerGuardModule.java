package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.event.UseItemEvent;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 被动观察可证实的异常，不将未知数据解释为服务器拒绝。
 * 上游降级：使用事件只证明尝试过交互，不证明放置成功；攻击未受伤也不能证明被吞。
 * 因此提示仅描述观察到的状态，Tab 列表不可完整读取时不推测假人。
 */
public final class ServerGuardModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final BooleanValue swallowBlock = add(new BooleanValue("Swallow Block", true));
    private final BooleanValue swallowAttack = add(new BooleanValue("Swallow Attack", true));
    private final BooleanValue rubberband = add(new BooleanValue("Rubberband Warning", true));
    private final NumberValue rubberbandRange = add(new NumberValue("Rubberband Range", 8, 1, 32, .5));
    private final BooleanValue botWarning = add(new BooleanValue("Bot Warning", true));
    private final BooleanValue tabCheck = add(new BooleanValue("Tab List Check", false));
    private final NumberValue botRange = add(new NumberValue("Bot Range", 64, 8, 128, 4));
    private final NumberValue cooldown = add(new NumberValue("Alert Cooldown", 0, 0, 5000, 50));
    private final BooleanValue flash = add(new BooleanValue("Flash", true));
    private final NumberValue duration = add(new NumberValue("Flash Duration", 1200, 200, 5000, 100));
    private final NumberValue thickness = add(new NumberValue("Flash Thickness", .30, .05, 1, .05));
    private final ColorValue color = add(new ColorValue("Flash Color", 0xFFFF2A2A));
    private final Set<UUID> tabIds = new HashSet<UUID>();
    private final double[] position = new double[3];
    private Object target;
    private long attackAt, blockAt;
    private final long[] lastAlert = new long[4];
    private int blockX, blockY, blockZ;
    private boolean observedTarget, hurtObserved;
    private int ticks;
    private volatile long flashStart, flashEnd;
    private GameBridge scanBridge;
    private PlayerState scanState;

    public ServerGuardModule() { super("Server Guard", Category.RENDER); }

    @Override protected void onStart() {
        reset();
        listen(AttackEvent.class, this::attack);
        listen(UseItemEvent.class, this::use);
        listen(PacketEvent.class, this::packet);
        listen(WorldChangeEvent.class, event -> reset());
    }
    @Override protected void onStop() { reset(); }
    private void reset() {
        target = null; attackAt = blockAt = 0;
        for (int i = 0; i < lastAlert.length; i++) lastAlert[i] = 0;
        flashStart = flashEnd = 0; ticks = 0; tabIds.clear();
    }
    private void use(UseItemEvent event) {
        if (event.post || !swallowBlock.get()) return;
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (bridge == null || actions == null) return;
        int[] pos = bridge.hitBlockPosition(actions.pick(5.0, 1f));
        blockAt = 0;
        if (pos != null) {
            blockX = pos[0]; blockY = pos[1]; blockZ = pos[2];
            blockAt = System.nanoTime();
        }
    }

    private void packet(PacketEvent event) {
        if (event.direction != PacketEvent.Direction.RECEIVE || event.packet == null) return;
        GameBridge bridge = bridge();
        if (bridge == null) return;
        long now = System.nanoTime();
        if (swallowBlock.get() && blockAt != 0 && now - blockAt <= 400_000_000L
                && bridge.isInstance(event.packet, ClassType.CLIENTBOUND_BLOCK_UPDATE)) {
            int[] pos = bridge.blockUpdatePosition(event.packet);
            if (pos != null && pos[0] == blockX && pos[1] == blockY && pos[2] == blockZ) {
                Object state = bridge.callMapped(event.packet, ClassType.CLIENTBOUND_BLOCK_UPDATE, "getBlockState");
                if (state != null && bridge.isAir(state)) {
                    blockAt = 0;
                    alert(bridge, "Server Guard: block update was air after use; placement not confirmed", 0, now);
                }
            }
        }
        if (rubberband.get() && bridge.isInstance(event.packet, ClassType.CLIENTBOUND_PLAYER_POSITION))
            positionPacket(bridge, event.packet, now);
    }

    private void positionPacket(GameBridge bridge, Object packet, long now) {
        PlayerState state = state();
        if (state == null || !state.present()) return;
        Object change = bridge.callMapped(packet, ClassType.CLIENTBOUND_PLAYER_POSITION, "change");
        Object flags;
        Object x, y, z;
        ClassType relative;
        if (change != null) {
            Object vector = bridge.callMapped(change, ClassType.POSITION_MOVE_ROTATION, "position");
            x = bridge.readField(vector, ClassType.VEC3, "x");
            y = bridge.readField(vector, ClassType.VEC3, "y");
            z = bridge.readField(vector, ClassType.VEC3, "z");
            flags = bridge.callMapped(packet, ClassType.CLIENTBOUND_PLAYER_POSITION, "relatives");
            relative = ClassType.RELATIVE;
        } else {
            x = bridge.callMapped(packet, ClassType.CLIENTBOUND_PLAYER_POSITION, "getX");
            y = bridge.callMapped(packet, ClassType.CLIENTBOUND_PLAYER_POSITION, "getY");
            z = bridge.callMapped(packet, ClassType.CLIENTBOUND_PLAYER_POSITION, "getZ");
            flags = bridge.callMapped(packet, ClassType.CLIENTBOUND_PLAYER_POSITION, "getRelativeArguments");
            relative = ClassType.RELATIVE_MOVEMENT;
        }
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)
                || !(flags instanceof Set)) return;
        Class<?> flagType = bridge.type(relative);
        if (flagType == null || !flagType.isEnum()) return;
        boolean rx = false, ry = false, rz = false;
        for (Object flag : (Set<?>) flags) {
            if (!flagType.isInstance(flag) || !(flag instanceof Enum)) return;
            String name = ((Enum<?>) flag).name();
            if ("X".equals(name)) rx = true;
            else if ("Y".equals(name)) ry = true;
            else if ("Z".equals(name)) rz = true;
            else if (!"X_ROT".equals(name) && !"Y_ROT".equals(name)
                    && !"DELTA_X".equals(name) && !"DELTA_Y".equals(name)
                    && !"DELTA_Z".equals(name) && !"ROTATE_DELTA".equals(name)) return;
        }
        double dx = ((Number) x).doubleValue() - (rx ? 0 : state.x());
        double dy = ((Number) y).doubleValue() - (ry ? 0 : state.y());
        double dz = ((Number) z).doubleValue() - (rz ? 0 : state.z());
        double range = rubberbandRange.get();
        if (dx * dx + dy * dy + dz * dz > range * range)
            alert(bridge, "Server Guard: server position correction exceeds range", 2, now);
    }
    private void attack(AttackEvent event) {
        if (!swallowAttack.get() || !event.post || event.target == null) return;
        GameBridge bridge = bridge();
        if (bridge == null || !bridge.isInstance(event.target, ClassType.LIVING_ENTITY)) return;
        Object hurt = bridge.readField(event.target, ClassType.LIVING_ENTITY, "hurtTime");
        if (!(hurt instanceof Number) || ((Number) hurt).intValue() != 0) return;
        target = event.target; attackAt = System.nanoTime(); hurtObserved = false;
    }
    @Override public void onTick() {
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || state == null || !state.present()) { target = null; return; }
        long now = System.nanoTime();
        if (target != null) {
            Object hurt = bridge.readField(target, ClassType.LIVING_ENTITY, "hurtTime");
            if (!(hurt instanceof Number)) target = null;
            else {
                if (((Number) hurt).intValue() > 0) hurtObserved = true;
                if (now - attackAt >= 300_000_000L) {
                    observedTarget = false;
                    scanBridge = bridge; scanState = null;
                    RenderSupport.forEachEntity(bridge, this);
                    if (observedTarget && !hurtObserved && swallowAttack.get())
                        alert(bridge, "Server Guard: no hurt animation observed after attack", 1, now);
                    target = null;
                }
            }
        }
        if (++ticks < 20) return;
        ticks = 0;
        if (!botWarning.get() || !tabCheck.get() || !readTab(bridge, state.player())) return;
        scanBridge = bridge; scanState = state;
        RenderSupport.forEachEntity(bridge, this);
        scanState = null;
    }
    private boolean readTab(GameBridge bridge, Object player) {
        Object entries = bridge.callMapped(bridge.connection(), ClassType.CLIENT_PACKET_LISTENER, "getOnlinePlayers");
        if (!(entries instanceof Iterable)) return false;
        tabIds.clear();
        for (Object entry : (Iterable<?>) entries) {
            Object profile = bridge.callMapped(entry, ClassType.PLAYER_INFO, "getProfile");
            Object id = bridge.callMapped(profile, ClassType.GAME_PROFILE, "getId");
            if (!(id instanceof UUID)) { tabIds.clear(); return false; }
            tabIds.add((UUID) id);
        }
        UUID self = bridge.entityUuid(player);
        return self != null && tabIds.contains(self);
    }
    @Override public void visit(Object entity) {
        if (scanState == null) { if (entity == target) observedTarget = true; return; }
        GameBridge bridge = scanBridge;
        if (entity == scanState.player() || !bridge.isInstance(entity, ClassType.PLAYER)
                || !bridge.entityPosition(entity, position)) return;
        UUID id = bridge.entityUuid(entity);
        if (id == null || tabIds.contains(id)) return;
        double x = position[0] - scanState.x(), y = position[1] - scanState.y(), z = position[2] - scanState.z();
        double range = botRange.get();
        if (x * x + y * y + z * z <= range * range) {
            long now = System.nanoTime();
            long previous = lastAlert[3];
            // 冷却未过就不拼告警串（onTick 扫描路径，避免每个命中实体都分配 String）。
            if (previous != 0 && now - previous < cooldown.asInt() * 1_000_000L) return;
            alert(bridge, "Server Guard: nearby player absent from tab list: " + bridge.entityName(entity), 3, now);
        }
    }
    private void alert(GameBridge bridge, String message, int kind, long now) {
        long previous = lastAlert[kind];
        if (previous != 0 && now - previous < cooldown.asInt() * 1_000_000L) return;
        lastAlert[kind] = now;
        bridge.localMessage(message);
        if (flash.get()) { flashStart = now; flashEnd = now + duration.asInt() * 1_000_000L; }
    }
    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        long start = flashStart, end = flashEnd, now = System.nanoTime();
        if (!flash.get() || end <= start || now >= end) return;
        float fade = (float) (end - now) / (end - start);
        float width = draw.width(), height = draw.height();
        float depth = Math.min(width, height) * thickness.asFloat(), step = depth / 8f;
        for (int i = 0; i < 8; i++) {
            int argb = RenderSupport.withAlpha(color.argb(), fade * (8 - i) / 8f);
            float offset = i * step;
            draw.rect(0, offset, width, step, argb);
            draw.rect(0, height - offset - step, width, step, argb);
            draw.rect(offset, 0, step, height, argb);
            draw.rect(width - offset - step, 0, step, height, argb);
        }
    }
}
