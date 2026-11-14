package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.*;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.hud.HudAnchor;
import dev.nocturne.client.hud.HudElement;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.HudElementModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.value.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 胜利横幅；上游降级：无头像和模糊，目标离开实体列表不等同于确认为击杀。 */
public final class VictoryBannerElementModule extends HudElementModule implements RenderSupport.EntityVisitor {
    private static final List<String> TRIGGERS = Arrays.asList("胜利", "挑战成功", "Victory");
    private final ModeValue mode = add(new ModeValue("Trigger Mode", "Kill", "Off", "Title", "Kill", "Chat"));
    private final MultiBoolValue titleTriggers = add(new MultiBoolValue("Title Triggers", TRIGGERS, TRIGGERS));
    private final MultiBoolValue chatTriggers = add(new MultiBoolValue("Chat Triggers", TRIGGERS, TRIGGERS));
    private final TextValue title = add(new TextValue("Title", "TARGET ELIMINATED", 256));
    private final TextValue subtitle = add(new TextValue("Subtitle", "ELIMINATION CONFIRMED", 256));
    private final NumberValue hold = add(new NumberValue("Hold Time", 1.9, .2, 10, .1));
    private final NumberValue scale = add(new NumberValue("Scale", .75, .5, 2, .05));
    private final NumberValue titleScale = add(new NumberValue("Title Scale", 1.5, .8, 1.8, .05));
    private final NumberValue subtitleScale = add(new NumberValue("Subtitle Scale", .7, .6, 1.2, .05));
    private final ColorValue accent = add(new ColorValue("Accent Color", 0xFFFFF414));
    private final ColorValue subColor = add(new ColorValue("Subtitle Color", 0xFF8D9194));
    private final ColorValue background = add(new ColorValue("Background Color", 0x9E0E0F11));
    private final ModeValue anchor = add(new ModeValue("Anchor", "TOP_CENTER", "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"));
    private final HudElement hud = new Panel();
    private final AtomicReference<Object> attacked = new AtomicReference<Object>();
    private EventBus.Subscription attackSubscription, packetSubscription, worldSubscription;
    private Object target, world;
    private GameBridge scanBridge;
    private int targetId = -1;
    private boolean found;
    private volatile long started;
    public VictoryBannerElementModule() { super("Victory Banner"); }
    @Override protected HudElement element() { return hud; }
    @Override protected void onHudStart() {
        NocturneClient c = NocturneClient.get(); if (c == null) return;
        try {
            attackSubscription = c.events().subscribe(AttackEvent.class, this::attack);
            packetSubscription = c.events().subscribe(PacketEvent.class, this::packet);
            worldSubscription = c.events().subscribe(WorldChangeEvent.class, event -> clear());
        } catch (RuntimeException failure) {
            onHudStop();
            throw failure;
        } catch (Error failure) {
            onHudStop();
            throw failure;
        }
    }
    @Override protected void onHudStop() {
        NocturneClient c = NocturneClient.get();
        if (c != null) { if (attackSubscription != null) c.events().unsubscribe(attackSubscription); if (packetSubscription != null) c.events().unsubscribe(packetSubscription); if (worldSubscription != null) c.events().unsubscribe(worldSubscription); }
        attackSubscription = packetSubscription = worldSubscription = null; clear();
    }
    private void clear() { attacked.set(null); target = null; targetId = -1; world = null; started = 0; }
    private void attack(AttackEvent e) { if (mode.is("Kill") && e.post && !e.cancelled && e.target != null) attacked.set(e.target); }
    private void packet(PacketEvent e) {
        if (e.direction != PacketEvent.Direction.RECEIVE || e.packet == null) return;
        NocturneClient c = NocturneClient.get(); GameBridge b = c == null ? null : c.gameBridge(); if (b == null) return;
        String text; MultiBoolValue triggers;
        if (mode.is("Title") && b.isInstance(e.packet, ClassType.CLIENTBOUND_SET_TITLE_TEXT)) {
            text = b.componentText(b.readField(e.packet, ClassType.CLIENTBOUND_SET_TITLE_TEXT, "text")); triggers = titleTriggers;
        } else if (mode.is("Chat") && b.isInstance(e.packet, ClassType.CLIENTBOUND_SYSTEM_CHAT)) {
            text = b.systemChatText(e.packet); triggers = chatTriggers;
        } else return;
        if (text == null) return;
        for (int i = 0; i < TRIGGERS.size(); i++) { String trigger = TRIGGERS.get(i); if (triggers.selected(trigger) && text.contains(trigger)) { started = System.currentTimeMillis(); return; } }
    }
    @Override public void onTick() {
        NocturneClient c = NocturneClient.get(); GameBridge b = c == null ? null : c.gameBridge();
        Object current = b == null ? null : b.world();
        if (current == null) { clear(); return; }
        if (current != world) { clear(); world = current; }
        if (!mode.is("Kill")) { target = null; targetId = -1; attacked.set(null); return; }
        Object latest = attacked.getAndSet(null);
        if (latest != null) { target = latest; targetId = b.entityId(latest); }
        if (targetId < 0 || b.entities() == null) return;
        found = false; scanBridge = b;
        try { RenderSupport.forEachEntity(b, this); } finally { scanBridge = null; }
        if (!found) { started = System.currentTimeMillis(); target = null; targetId = -1; }
    }
    @Override public void visit(Object entity) { if (entity == target && scanBridge.entityId(entity) == targetId) found = true; }
    private final class Panel implements HudElement {
        public String id() { return name(); }
        public HudAnchor anchor() { return HudAnchor.valueOf(anchor.get()); }
        private float opacity() { if (started == 0) return 0; long age = System.currentTimeMillis() - started; double end = 200 + hold.get() * 1000; return age < 200 ? Math.max(0, age / 200f) : age < end ? 1 : (float) Math.max(0, 1 - (age - end) / 300); }
        private float ts() { return 10 * scale.asFloat() * titleScale.asFloat(); }
        private float ss() { return 10 * scale.asFloat() * subtitleScale.asFloat(); }
        public float width(OverlayDraw d) { return opacity() <= 0 ? 0 : Math.max(d.textWidth(title.get(), ts()), d.textWidth(subtitle.get(), ss())) + 40 * scale.asFloat(); }
        public float height(OverlayDraw d) { return opacity() <= 0 ? 0 : d.textHeight(ts()) + d.textHeight(ss()) + 16 * scale.asFloat(); }
        public void draw(OverlayDraw d, float x, float y) {
            float a = opacity(); if (a <= 0) return; float s = scale.asFloat(), w = width(d), h = height(d);
            d.roundedRect(x, y, w, h, 5 * s, RenderSupport.withAlpha(background.argb(), a));
            d.rect(x, y + 4 * s, 2 * s, h - 8 * s, RenderSupport.withAlpha(accent.argb(), a));
            d.text(title.get(), x + (w - d.textWidth(title.get(), ts())) / 2, y + 8 * s, ts(), RenderSupport.withAlpha(accent.argb(), a));
            d.text(subtitle.get(), x + (w - d.textWidth(subtitle.get(), ss())) / 2, y + 8 * s + d.textHeight(ts()), ss(), RenderSupport.withAlpha(subColor.argb(), a));
        }
    }
}
