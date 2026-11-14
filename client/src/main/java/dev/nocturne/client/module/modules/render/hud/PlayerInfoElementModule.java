package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.control.Friends;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.hud.HudAnchor;
import dev.nocturne.client.hud.HudElement;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.HudElementModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 附近玩家列表；头像、模糊和阴影没有对应绘制能力，图腾仅统计启用后收到的事件。
 *
 * <p>上游降级（阶段 10）：删 {@code Show Heads}——本仓库无玩家头像/皮肤绘制钩子，
 * 列表行改画 {@code ●} 占位，不注册死开关。
 */
public final class PlayerInfoElementModule extends HudElementModule implements RenderSupport.EntityVisitor {
    private final NumberValue scale = add(new NumberValue("Scale", 1, .5, 2, .05));
    private final NumberValue width = add(new NumberValue("Width", 250, 200, 300, 1));
    private final NumberValue range = add(new NumberValue("Range", 128, 8, 256, 1));
    private final NumberValue maxPlayers = add(new NumberValue("Max Players", 8, 1, 20, 1));
    private final BooleanValue showSelf = add(new BooleanValue("Show Self", false));
    private final NumberValue radius = add(new NumberValue("Corner Radius", 6, 0, 20, .5));
    private final ColorValue background = add(new ColorValue("Background Color", 0x96121815));
    private final ColorValue headerColor = add(new ColorValue("Header Color", 0xF0FFFFFF));
    private final ColorValue textColor = add(new ColorValue("Text Color", 0xE6F0F3F1));
    private final ModeValue anchor = add(new ModeValue("Anchor", "TOP_LEFT", "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"));
    private final Object[] entities = new Object[20];
    private final double[] distances = new double[20], localPos = new double[3], pos = new double[3];
    private final String[] names = new String[20], distanceText = new String[20], healthText = new String[20];
    private final int[] colors = new int[20];
    private final Map<String, Integer> pops = new HashMap<String, Integer>();
    private final ConcurrentLinkedQueue<Integer> pending = new ConcurrentLinkedQueue<Integer>();
    private EventBus.Subscription packetSubscription;
    private GameBridge bridge;
    private Object self, world;
    private int count, pendingId = -1;
    private volatile boolean available;
    private String header = "Players (0)";
    private final HudElement element = new HudElement() {
        public String id() { return name(); }
        public HudAnchor anchor() { return HudAnchor.valueOf(anchor.get()); }
        public float width(OverlayDraw d) { return available ? width.asFloat() * scale.asFloat() : 0; }
        public float height(OverlayDraw d) { return available ? (count + 1) * 10 * scale.asFloat() + 8 * scale.asFloat() : 0; }
        public void draw(OverlayDraw d, float x, float y) {
            if (!available) return;
            float s = scale.asFloat(), w = width(d), size = 8 * s;
            d.roundedRect(x, y, w, height(d), radius.asFloat() * s, background.argb());
            d.text(header, x + 4 * s, y + 3 * s, size, headerColor.argb());
            for (int i = 0; i < count; i++) {
                float row = y + (13 + i * 10) * s;
                d.text("●", x + 4 * s, row, size, colors[i]);
                d.text(names[i], x + 14 * s, row, size, textColor.argb());
                d.text(distanceText[i], x + w * .76f - d.textWidth(distanceText[i], size), row, size, textColor.argb());
                d.text(healthText[i], x + w - 4 * s - d.textWidth(healthText[i], size), row, size, textColor.argb());
            }
        }
    };
    public PlayerInfoElementModule() { super("PlayerInfo"); }
    @Override protected HudElement element() { return element; }
    @Override protected void onHudStart() {
        NocturneClient client = NocturneClient.get();
        if (client != null) packetSubscription = client.events().subscribe(PacketEvent.class, this::packet);
    }
    @Override protected void onHudStop() {
        NocturneClient client = NocturneClient.get();
        if (client != null && packetSubscription != null) client.events().unsubscribe(packetSubscription);
        packetSubscription = null; available = false; world = null; pending.clear(); pops.clear();
    }
    private void packet(PacketEvent event) {
        if (event.direction != PacketEvent.Direction.RECEIVE) return;
        NocturneClient client = NocturneClient.get();
        GameBridge b = client == null ? null : client.gameBridge();
        if (b == null || !b.isInstance(event.packet, ClassType.CLIENTBOUND_ENTITY_EVENT)) return;
        int[] data = b.entityEventData(event.packet);
        if (data != null && data[0] == 35 && data[1] >= 0) pending.offer(data[1]);
    }
    @Override public void onTick() {
        NocturneClient client = NocturneClient.get();
        bridge = client == null ? null : client.gameBridge();
        if (bridge == null || bridge.world() == null || bridge.player() == null) { available = false; world = null; pending.clear(); pops.clear(); return; }
        if (world != bridge.world()) { world = bridge.world(); pending.clear(); pops.clear(); }
        self = bridge.player();
        if (!bridge.entityPosition(self, localPos)) { available = false; return; }
        Integer id;
        while ((id = pending.poll()) != null) {
            pendingId = id.intValue();
            RenderSupport.forEachEntity(bridge, this);
            pendingId = -1;
        }
        count = 0;
        RenderSupport.forEachEntity(bridge, this);
        String nextHeader = "Players (" + count + ")";
        if (!nextHeader.equals(header)) header = nextHeader;
        for (int i = 0; i < count; i++) {
            Object entity = entities[i];
            String name = bridge.entityName(entity);
            Integer pop = pops.get(name);
            String label = pop == null ? name : name + " (" + pop + ")";
            if (!label.equals(names[i])) names[i] = label;
            String distance = Math.round(Math.sqrt(distances[i])) + "m";
            if (!distance.equals(distanceText[i])) distanceText[i] = distance;
            Float health = bridge.healthOrNull(entity);
            String hp = health == null ? "?" : String.valueOf(Math.round(health.floatValue() * 10f) / 10f);
            if (!hp.equals(healthText[i])) healthText[i] = hp;
            colors[i] = Friends.isFriend(bridge, entity) ? 0xFF55FF55 : bridge.isAllied(self, entity) ? 0xFF55FFFF : 0xFFFFFFFF;
        }
        available = true;
    }
    @Override public void visit(Object entity) {
        if (pendingId >= 0) {
            if (bridge.isInstance(entity, ClassType.PLAYER) && bridge.entityId(entity) == pendingId) {
                String name = bridge.entityName(entity);
                if (!name.isEmpty()) {
                    Integer old = pops.get(name);
                    pops.put(name, old == null ? 1 : old + 1);
                }
            }
            return;
        }
        if (!bridge.isInstance(entity, ClassType.PLAYER) || (!showSelf.get() && entity == self) || !bridge.entityPosition(entity, pos)) return;
        double x = pos[0] - localPos[0], y = pos[1] - localPos[1], z = pos[2] - localPos[2];
        double distance = x * x + y * y + z * z;
        if (distance > range.get() * range.get()) return;
        int limit = maxPlayers.asInt(), at = 0;
        while (at < count && distances[at] <= distance) at++;
        if (at >= limit) return;
        int end = Math.min(count, limit - 1);
        for (int i = end; i > at; i--) { entities[i] = entities[i - 1]; distances[i] = distances[i - 1]; }
        entities[at] = entity; distances[at] = distance;
        if (count < limit) count++;
    }
}
