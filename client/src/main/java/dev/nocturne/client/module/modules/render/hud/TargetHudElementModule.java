package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.hud.HudAnchor;
import dev.nocturne.client.hud.HudElement;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.HudElementModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.value.*;

/** 目标信息面板；上游降级：无头像、物品模型及模糊。护甲映射缺失时不显示护甲行。 */
public final class TargetHudElementModule extends HudElementModule {
    private final ModeValue style = add(new ModeValue("Style", "Modern", "Modern", "Akrien"));
    private final ModeValue anchor = add(new ModeValue("Anchor", "BOTTOM_CENTER", "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"));
    private final NumberValue scale = add(new NumberValue("Scale", .9, .5, 2, .1));
    private final NumberValue width = add(new NumberValue("Width", 150, 100, 300, 1));
    private final NumberValue height = add(new NumberValue("Height", 52, 30, 100, 1));
    private final NumberValue radius = add(new NumberValue("Radius", 5, 0, 20, 1));
    private final NumberValue barHeight = add(new NumberValue("Bar Height", 5, 2, 20, 1));
    private final NumberValue barRadius = add(new NumberValue("Bar Radius", 2, 0, 15, 1));
    private final NumberValue nameSize = add(new NumberValue("Name Size", 10.5, 8, 18, .5));
    private final BooleanValue delayBar = add(new BooleanValue("Delay Bar", true));
    private final BooleanValue delayWait = add(new BooleanValue("Delay Wait", true));
    private final NumberValue delayTime = add(new NumberValue("Delay Time", 250, 0, 500, 50));
    private final NumberValue delaySpeed = add(new NumberValue("Delay Speed", 2, .1, 10, .1));
    private final BooleanValue outline = add(new BooleanValue("Bar Outline", true));
    private final NumberValue outlineWidth = add(new NumberValue("Bar Outline Width", 1, .5, 5, .5));
    private final ColorValue background = add(new ColorValue("Background Color", 0x320F0F0F));
    private final ColorValue barBackground = add(new ColorValue("Bar Background Color", 0x37FFFFFF));
    private final ColorValue fill = add(new ColorValue("Bar Fill Color", 0xEBFFECF8));
    private final ColorValue delayedColor = add(new ColorValue("Delay Bar Color", 0x64BEBEBE));
    private final ColorValue outlineColor = add(new ColorValue("Bar Outline Color", 0x55FFFFFF));
    private final ColorValue textColor = add(new ColorValue("Text Color", 0xEBFFFFFF));
    private final TargetManager.Options options = new TargetManager.Options();
    private final HudElement hud = new Panel();
    private Object target;
    private String label = "", armor = "", cachedName = "";
    private int distance = -1, armorValue = Integer.MIN_VALUE;
    private float ratio, delayed;
    private long lastSeen, damageAt, lastTick;
    public TargetHudElementModule() { super("Target HUD"); options.range = 4; options.mobs = true; options.animals = true; }
    @Override protected HudElement element() { return hud; }
    @Override protected void onHudStop() { target = null; lastSeen = 0; lastTick = 0; }
    @Override public void onTick() {
        NocturneClient client = NocturneClient.get();
        GameBridge bridge = client == null ? null : client.gameBridge();
        long now = System.currentTimeMillis();
        TargetManager.Target next = bridge == null ? null : client.targets().find(client, options);
        if (next == null) { target = null; return; }
        Object entity = next.entity();
        float max = bridge.maxHealth(entity);
        float value = max > 0 ? Math.max(0, Math.min(1, bridge.health(entity) / max)) : 0;
        if (target != entity) { delayed = value; damageAt = now; }
        else if (value < ratio) damageAt = now;
        if (value > delayed) delayed = value;
        if (!delayWait.get() || now - damageAt >= delayTime.get()) {
            double dt = lastTick == 0 ? 0 : Math.min(1, (now - lastTick) / 1000.0);
            delayed += (value - delayed) * (float) (1 - Math.exp(-delaySpeed.get() * dt));
        }
        target = entity; ratio = value; lastSeen = now; lastTick = now;
        String name = bridge.entityName(entity);
        int meters = (int) next.distance();
        if (meters != distance || !name.equals(cachedName)) { distance = meters; cachedName = name; label = name + "  " + meters + "m"; }
        if (style.is("Akrien")) {
            Object ar = bridge.callMapped(entity, ClassType.LIVING_ENTITY, "getArmorValue");
            int n = ar instanceof Number ? ((Number) ar).intValue() : Integer.MIN_VALUE;
            if (n != armorValue) { armorValue = n; armor = n == Integer.MIN_VALUE ? "" : "AR " + n; }
        }
    }
    private final class Panel implements HudElement {
        public String id() { return name(); }
        public HudAnchor anchor() { return HudAnchor.valueOf(anchor.get()); }
        private float alpha() { return target != null ? 1 : lastSeen == 0 ? 0 : Math.max(0, Math.min(1, 1 - (System.currentTimeMillis() - lastSeen) / 200f)); }
        public float width(OverlayDraw d) { return alpha() > 0 ? width.asFloat() * scale.asFloat() : 0; }
        public float height(OverlayDraw d) { return alpha() > 0 ? (height.asFloat() + (style.is("Akrien") ? 10 : 0)) * scale.asFloat() : 0; }
        public void draw(OverlayDraw d, float x, float y) {
            float a = alpha(); if (a <= 0) return;
            float s = scale.asFloat(), w = width(d), h = height(d), pad = 6 * s;
            d.roundedRect(x, y, w, h, radius.asFloat() * s, RenderSupport.withAlpha(background.argb(), a));
            d.text(label, x + pad, y + pad, nameSize.asFloat() * s, RenderSupport.withAlpha(textColor.argb(), a));
            if (style.is("Akrien") && !armor.isEmpty()) d.text(armor, x + pad, y + pad + nameSize.asFloat() * s, 8 * s, RenderSupport.withAlpha(textColor.argb(), a));
            float bx = x + pad, bw = Math.max(0, w - 2 * pad), bh = barHeight.asFloat() * s, by = y + h - pad - bh;
            float r = barRadius.asFloat() * s;
            d.roundedRect(bx, by, bw, bh, r, RenderSupport.withAlpha(barBackground.argb(), a));
            if (delayBar.get()) d.roundedRect(bx, by, bw * delayed, bh, r, RenderSupport.withAlpha(delayedColor.argb(), a));
            d.roundedRect(bx, by, bw * ratio, bh, r, RenderSupport.withAlpha(fill.argb(), a));
            if (outline.get()) d.outline(bx, by, bw, bh, outlineWidth.asFloat() * s, RenderSupport.withAlpha(outlineColor.argb(), a));
        }
    }
}
