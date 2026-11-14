package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.hud.HudAnchor;
import dev.nocturne.client.hud.HudElement;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.HudElementModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 方块物品计数；缺少 Scaffold 或背包能力时隐藏，不猜测方块数量。 */
public final class ScaffoldBlockElementModule extends HudElementModule {
    private final NumberValue scale = add(new NumberValue("Scale", .7, .5, 2, .1));
    private final NumberValue radius = add(new NumberValue("Corner Radius", 5, 0, 20, .5));
    private final ColorValue background = add(new ColorValue("Background Color", 0x320F0F0F));
    private final ColorValue color = add(new ColorValue("Text Color", 0xF5F8F9FC));
    private final ColorValue secondary = add(new ColorValue("Text Secondary", 0xAAD2D6E1));
    private final BooleanValue smooth = add(new BooleanValue("Smooth Number", true));
    private final NumberValue delay = add(new NumberValue("Number Delay", .15, 0, .5, .01));
    private final ModeValue anchor = add(new ModeValue("Anchor", "BOTTOM_CENTER", "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"));
    private volatile boolean available;
    private volatile int target;
    private double displayed;
    private long lastTime;
    private int lastNumber = Integer.MIN_VALUE;
    private String number = "";
    private final HudElement element = new HudElement() {
        public String id() { return name(); }
        public HudAnchor anchor() { return HudAnchor.valueOf(anchor.get()); }
        public float width(OverlayDraw d) { return visible() ? 70 * scale.asFloat() : 0; }
        public float height(OverlayDraw d) { return visible() ? 22 * scale.asFloat() : 0; }
        public void draw(OverlayDraw d, float x, float y) {
            if (!visible()) { lastTime = 0; return; }
            long now = System.nanoTime();
            if (lastTime == 0 || !smooth.get() || delay.get() <= 0) displayed = target;
            else displayed += (target - displayed) * (1 - Math.exp(-(now - lastTime) / 1.0e9 / delay.get()));
            lastTime = now;
            int value = (int) Math.round(displayed);
            if (value != lastNumber) { lastNumber = value; number = Integer.toString(value); }
            float s = scale.asFloat(), size = 9 * s;
            d.roundedRect(x, y, width(d), height(d), radius.asFloat() * s, background.argb());
            d.text("Blocks", x + 4 * s, y + 6 * s, size, secondary.argb());
            d.text(number, x + width(d) - 4 * s - d.textWidth(number, size), y + 6 * s, size, color.argb());
        }
    };
    public ScaffoldBlockElementModule() { super("Scaffold Block"); }
    @Override protected HudElement element() { return element; }
    private boolean visible() {
        NocturneClient client = NocturneClient.get();
        Module scaffold = client == null ? null : client.modules().get("Scaffold");
        return available && scaffold != null && scaffold.isEnabled();
    }
    @Override protected void onHudStop() { available = false; lastTime = 0; }
    @Override public void onTick() {
        NocturneClient client = NocturneClient.get();
        Module scaffold = client == null ? null : client.modules().get("Scaffold");
        GameBridge bridge = client == null ? null : client.gameBridge();
        if (scaffold == null || !scaffold.isEnabled() || bridge == null || bridge.inventory() == null || bridge.type(ClassType.BLOCK_ITEM) == null) { available = false; return; }
        int total = 0;
        for (int i = 0; i < 36; i++) {
            Object stack = bridge.inventoryStack(i);
            if (!bridge.isEmptyStack(stack) && bridge.isInstance(bridge.callMapped(stack, ClassType.ITEM_STACK, "getItem"), ClassType.BLOCK_ITEM)) total += bridge.itemCount(stack);
        }
        target = total;
        available = true;
    }
}
