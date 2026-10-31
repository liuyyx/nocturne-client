package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.RenderEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.TextValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 找到已加载区块中的刷怪笼，按实体白名单画框和名称标签。 */
public final class SpawnerFinderModule extends Module implements WorldOverlay {

    private final BooleanValue showDistance = add(new BooleanValue("Show distance", true));
    private final TextValue spawners = add(new TextValue(
            "Spawner names-whitelist", "Zombie, Skeleton, Creeper, Spider", 160));

    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();
    private volatile EventBus.Subscription renderSubscription;
    private boolean gateLogged;
    private int lastVisibleCount;

    @Override
    public String name() {
        return "SpawnerFinder";
    }

    @Override
    public Category category() {
        return Category.RENDER;
    }

    @Override
    protected void onEnable() {
        gateLogged = false;
        lastVisibleCount = 0;
        subscribe();
    }

    @Override
    protected void onDisable() {
        unsubscribe();
        lastVisibleCount = 0;
    }

    @Override
    public void onTick() {
        synchronized (this) {
            if (isEnabled() && renderSubscription == null) {
                subscribe();
            }
        }
    }

    public int lastVisibleCount() {
        return lastVisibleCount;
    }

    private void subscribe() {
        NocturneClient client = NocturneClient.get();
        if (client == null || renderSubscription != null) {
            return;
        }
        try {
            renderSubscription = client.events().subscribe(RenderEvent.class, this::onRender);
        } catch (Throwable t) {
            renderSubscription = null;
        }
    }

    private void unsubscribe() {
        EventBus.Subscription subscription = renderSubscription;
        renderSubscription = null;
        if (subscription == null) {
            return;
        }
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            client.events().unsubscribe(subscription);
        }
    }

    private void onRender(RenderEvent event) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        if (bridge.type(ClassType.SPAWNER_BLOCK_ENTITY) == null) {
            if (!gateLogged) {
                gateLogged = true;
                System.out.println("[nocturne] SpawnerFinder: 刷怪笼方块实体缺失（版本面不可用），已禁用");
            }
            lastVisibleCount = 0;
            return;
        }
        lastVisibleCount = 0;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null || bridge.type(ClassType.SPAWNER_BLOCK_ENTITY) == null) {
            return;
        }
        List<String> allowed = whitelist();
        PlayerState self = self();
        int visible = 0;
        for (Object blockEntity : BlockEntityScan.loadedBlockEntities(bridge)) {
            if (!bridge.isInstance(blockEntity, ClassType.SPAWNER_BLOCK_ENTITY)) {
                continue;
            }
            String name = spawnerName(bridge, blockEntity);
            if (!matches(name, allowed)) {
                continue;
            }
            Object pos = bridge.callMapped(blockEntity, ClassType.BLOCK_ENTITY, "getBlockPos");
            if (pos == null) {
                continue;
            }
            double x = coordinate(bridge, pos, "getX");
            double y = coordinate(bridge, pos, "getY");
            double z = coordinate(bridge, pos, "getZ");
            if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
                continue;
            }
            projection.project(x + 0.5d, y + 1.25d, z + 0.5d, point);
            if (!point.visible) {
                continue;
            }
            draw.outline(point.x - 4.0f, point.y - 4.0f, 8.0f, 8.0f, 1.5f, 0xFFFF3030);
            StringBuilder label = new StringBuilder(pretty(name));
            if (showDistance.get() && self.present()) {
                double dx = x + 0.5d - self.x();
                double dy = y + 0.5d - self.y();
                double dz = z + 0.5d - self.z();
                label.append(" [").append((int) Math.sqrt(dx * dx + dy * dy + dz * dz)).append(']');
            }
            String text = label.toString();
            float width = draw.textWidth(text);
            float height = draw.textHeight();
            draw.rect(point.x - width * 0.5f - 2.0f, point.y - height - 2.0f, width + 4.0f,
                    height + 2.0f, 0x80101010);
            draw.text(text, point.x - width * 0.5f, point.y - height - 1.0f, 0xFFFFFFFF);
            visible++;
        }
        lastVisibleCount = visible;
    }

    private static String spawnerName(GameBridge bridge, Object blockEntity) {
        Object spawner = bridge.callMapped(blockEntity, ClassType.SPAWNER_BLOCK_ENTITY, "getSpawner");
        if (spawner == null) {
            return null;
        }
        Object name = bridge.callMapped(spawner, ClassType.BASE_SPAWNER, "getEntityNameToSpawn");
        if (name instanceof String && !((String) name).isEmpty()) {
            return (String) name;
        }
        Object pos = bridge.callMapped(blockEntity, ClassType.BLOCK_ENTITY, "getBlockPos");
        Object level = BlockEntityScan.level(bridge);
        if (pos == null || level == null) {
            return null;
        }
        Object display = bridge.callMapped(spawner, ClassType.BASE_SPAWNER,
                "getOrCreateDisplayEntity", level, pos);
        return display == null ? null : display.getClass().getSimpleName();
    }

    private List<String> whitelist() {
        List<String> out = new ArrayList<String>();
        for (String part : spawners.get().split(",")) {
            String entry = normalize(part);
            if (!entry.isEmpty()) {
                out.add(entry);
            }
        }
        return out;
    }

    static boolean matches(String name, List<String> allowed) {
        if (name == null) {
            return false;
        }
        if (allowed.isEmpty()) {
            return true;
        }
        String normalized = normalize(name);
        for (String entry : allowed) {
            if (normalize(entry).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < trimmed.length()) {
            trimmed = trimmed.substring(colon + 1);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String pretty(String name) {
        String normalized = normalize(name);
        if (normalized.isEmpty()) {
            return "Spawner";
        }
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static double coordinate(GameBridge bridge, Object pos, String member) {
        Object value = bridge.callMapped(pos, ClassType.BLOCK_POS, member);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    private static PlayerState self() {
        NocturneClient client = NocturneClient.get();
        if (client == null || client.actions() == null) {
            return PlayerState.empty();
        }
        return client.actions().readState();
    }

    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
