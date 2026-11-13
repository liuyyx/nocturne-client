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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家列表移除包驱动的退服快照；位置在 tick 更新，离线标签只在移除时构造。
 * 上游降级：Model 无模型提交管线，固定使用 Box，不提供只剩一个选项的无效 Render Mode 控件。
 * 没有独立玩家移除包的版本明确不可用，不把普通实体卸载冒充退服。
 */
public final class LogoutSpotsModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final BooleanValue notifications = add(new BooleanValue("Notifications", true));
    private final BooleanValue ignoreBots = add(new BooleanValue("Ignore Bots", true));
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0x19FFFFFF));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0x7FFFFFFF));
    private final Map<UUID, Snapshot> online = new HashMap<UUID, Snapshot>();
    private final Map<UUID, Snapshot> loggedOut = new HashMap<UUID, Snapshot>();
    private final List<Snapshot> spots = new ArrayList<Snapshot>();
    private final double[] position = new double[3];
    private Set<UUID> tabIds = Collections.emptySet();
    private boolean refreshTab = true;
    private boolean checked;
    private boolean available;
    private GameBridge tickBridge;

    public LogoutSpotsModule() { super("Logout Spots", Category.RENDER); }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, this::packet);
        listen(WorldChangeEvent.class, event -> clear());
    }

    @Override
    protected void onStop() { clear(); }

    private void clear() {
        online.clear(); loggedOut.clear(); spots.clear();
        tabIds = Collections.emptySet(); refreshTab = true;
    }

    private boolean available(GameBridge bridge) {
        if (!checked && bridge != null) {
            checked = true;
            available = !bridge.mapping().isClassAbsent(ClassType.CLIENTBOUND_PLAYER_INFO_REMOVE)
                    && !bridge.mapping().isMemberAbsent(ClassType.CLIENTBOUND_PLAYER_INFO_REMOVE, "profileIds");
            if (!available) System.out.println("[nocturne] Logout Spots: player removal packet unavailable on this version (mapping absent)");
        }
        return available;
    }

    @Override
    public void onTick() {
        tickBridge = bridge();
        if (!available(tickBridge)) return;
        if (refreshTab) {
            tabIds = tickBridge.onlinePlayerUuids();
            refreshTab = false;
            // 更新包处理完以后检查 Tab，而不是等玩家模型出现在世界里才清除旧标记。
            for (int i = spots.size() - 1; i >= 0; i--) {
                Snapshot snapshot = spots.get(i);
                if (tabIds.contains(snapshot.uuid)) {
                    spots.remove(i); loggedOut.remove(snapshot.uuid);
                    if (notifications.get()) tickBridge.localMessage(snapshot.name + " logged back at "
                            + (int) snapshot.x + "/" + (int) snapshot.z);
                }
            }
        }
        RenderSupport.forEachEntity(tickBridge, this);
    }

    @Override
    public void visit(Object entity) {
        GameBridge bridge = tickBridge;
        if (RenderSupport.isSelf(bridge, entity) || !bridge.isInstance(entity, ClassType.PLAYER)) return;
        UUID uuid = bridge.entityUuid(entity);
        if (uuid == null || !bridge.entityPosition(entity, position)) return;
        String name = bridge.entityName(entity);
        Snapshot snapshot = online.get(uuid);
        if (snapshot == null) {
            snapshot = new Snapshot(uuid);
            online.put(uuid, snapshot);
        }
        if (!name.equals(snapshot.name)) {
            snapshot.name = name;
            String upper = name.toUpperCase(Locale.ROOT);
            snapshot.botName = upper.contains("NPC") || upper.contains("BOT");
        }
        if (ignoreBots.get() && (snapshot.botName || !tabIds.contains(uuid))) {
            online.remove(uuid);
            return;
        }
        snapshot.x = position[0]; snapshot.y = position[1]; snapshot.z = position[2];
        snapshot.width = bridge.entityWidth(entity); snapshot.height = bridge.entityHeight(entity);
        snapshot.health = bridge.health(entity);
    }

    private void packet(PacketEvent event) {
        GameBridge bridge = bridge();
        if (event.direction != PacketEvent.Direction.RECEIVE || !available(bridge)) return;
        if (bridge.isInstance(event.packet, ClassType.CLIENTBOUND_PLAYER_INFO_UPDATE)) {
            refreshTab = true;
        } else if (bridge.isInstance(event.packet, ClassType.CLIENTBOUND_PLAYER_INFO_REMOVE)) {
            refreshTab = true;
            Object ids = bridge.readField(event.packet, ClassType.CLIENTBOUND_PLAYER_INFO_REMOVE, "profileIds");
            if (ids instanceof Iterable) {
                for (Object id : (Iterable<?>) ids) if (id instanceof UUID) remove((UUID) id, bridge);
            } else if (ids instanceof Object[]) {
                for (Object id : (Object[]) ids) if (id instanceof UUID) remove((UUID) id, bridge);
            } else if (ids instanceof UUID) {
                remove((UUID) ids, bridge);
            }
        }
    }

    private void remove(UUID uuid, GameBridge bridge) {
        Snapshot snapshot = online.remove(uuid);
        if (snapshot == null) return;
        snapshot.label = snapshot.name + " " + snapshot.health + " " + (int) snapshot.x + "/" + (int) snapshot.z;
        Snapshot previous = loggedOut.put(uuid, snapshot);
        if (previous != null) spots.remove(previous);
        spots.add(snapshot);
        if (notifications.get()) bridge.localMessage(snapshot.name + " logged out at "
                + (int) snapshot.x + "/" + (int) snapshot.z);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!available) return;
        for (int i = 0; i < spots.size(); i++) {
            Snapshot spot = spots.get(i);
            double radius = spot.width * .5;
            WorldGeometry.silhouette(draw, projection, spot.x - radius, spot.y, spot.z - radius,
                    spot.x + radius, spot.y + spot.height, spot.z + radius, sideColor.argb());
            WorldGeometry.box(draw, projection, spot.x - radius, spot.y, spot.z - radius,
                    spot.x + radius, spot.y + spot.height, spot.z + radius, 1f, lineColor.argb());
            WorldGeometry.label(draw, projection, spot.label, spot.x, spot.y + spot.height + .3,
                    spot.z, 8f, 0xFFFFFFFF);
        }
    }

    private static final class Snapshot {
        private final UUID uuid;
        private String name = "";
        private String label = "";
        private boolean botName;
        private double x, y, z, width = .6, height = 1.8;
        private float health;
        private Snapshot(UUID uuid) { this.uuid = uuid; }
    }
}
