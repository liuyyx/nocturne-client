package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.combat.AntiBotModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Logout Spots：记录退服玩家位置，Box/Model 轮廓与头顶信息。 */
public final class LogoutSpotsModule extends GameModule implements WorldOverlay {
    private final BooleanValue notifications = add(new BooleanValue("Notifications", true));
    private final BooleanValue ignoreBots = add(new BooleanValue("Ignore Bots", true));
    private final ModeValue renderMode = add(new ModeValue("Render Mode", "Model", "Model", "Box"));
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0x19FFFFFF));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0x7FFFFFFF));
    private final Map<UUID, Snapshot> visible = new HashMap<UUID, Snapshot>();
    private final Map<UUID, Snapshot> logout = new HashMap<UUID, Snapshot>();

    public LogoutSpotsModule() {
        super("Logout Spots", Category.RENDER);
    }

    @Override
    public void onTick() {
        Set<UUID> current = new HashSet<UUID>();
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (entity == bridge().player() || !bridge().isInstance(entity,
                    dev.nocturne.client.mapping.ClassType.PLAYER)) {
                return;
            }
            UUID uuid = bridge().entityUuid(entity);
            double[] pos = bridge().entityPosition(entity);
            if (uuid == null || pos == null || ignoreBots.get() && AntiBotModule.isBot(bridge(), entity)) {
                return;
            }
            current.add(uuid);
            Snapshot snapshot = new Snapshot(uuid, bridge().entityName(entity), pos,
                    bridge().health(entity) + bridge().maxHealth(entity) * 0.0f);
            visible.put(uuid, snapshot);
            if (logout.remove(uuid) != null && notifications.get()) {
                bridge().localMessage(snapshot.name + " logged back at " + coords(pos));
            }
        });
        for (UUID uuid : visible.keySet()) {
            if (!current.contains(uuid)) {
                Snapshot snapshot = visible.get(uuid);
                logout.put(uuid, snapshot);
                if (notifications.get()) {
                    bridge().localMessage(snapshot.name + " logged out at " + coords(snapshot.pos));
                }
            }
        }
        visible.keySet().retainAll(current);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        for (Snapshot snapshot : logout.values()) {
            WorldGeometry.box(draw, projection, snapshot.pos[0] - 0.3, snapshot.pos[1], snapshot.pos[2] - 0.3,
                    snapshot.pos[0] + 0.3, snapshot.pos[1] + 1.8, snapshot.pos[2] + 0.3,
                    1.0f, lineColor.argb());
            if (renderMode.is("Model")) {
                WorldGeometry.circle(draw, projection, snapshot.pos[0], snapshot.pos[1] + 1.6,
                        snapshot.pos[2], 0.25, 12, 1.0f, lineColor.argb());
                WorldGeometry.side(draw, projection, (int) Math.floor(snapshot.pos[0]),
                        (int) Math.floor(snapshot.pos[1]), (int) Math.floor(snapshot.pos[2]),
                        "up", 1.0f, sideColor.argb());
            } else {
                WorldGeometry.blockFilled(draw, projection, (int) Math.floor(snapshot.pos[0]),
                        (int) Math.floor(snapshot.pos[1]), (int) Math.floor(snapshot.pos[2]),
                        1.0f, sideColor.argb(), lineColor.argb());
            }
            WorldGeometry.label(draw, projection, snapshot.name + String.format(java.util.Locale.ROOT,
                            " %.1f %.0f/%.0f", snapshot.health, snapshot.pos[0], snapshot.pos[2]),
                    snapshot.pos[0], snapshot.pos[1] + 2.1, snapshot.pos[2], 0xFFFFFFFF);
        }
    }

    private static String coords(double[] pos) {
        return (int) pos[0] + "/" + (int) pos[1] + "/" + (int) pos[2];
    }

    @Override
    protected void onStop() {
        visible.clear();
        logout.clear();
    }

    private static final class Snapshot {
        private final UUID uuid;
        private final String name;
        private final double[] pos;
        private final float health;

        private Snapshot(UUID uuid, String name, double[] pos, float health) {
            this.uuid = uuid;
            this.name = name;
            this.pos = pos;
            this.health = health;
        }
    }
}
