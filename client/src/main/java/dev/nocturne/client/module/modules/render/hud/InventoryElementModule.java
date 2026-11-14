package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.hud.HudAnchor;
import dev.nocturne.client.hud.HudElement;
import dev.nocturne.client.module.HudElementModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 背包物品以文字代替图标；上游模糊和阴影没有对应绘制能力。 */
public final class InventoryElementModule extends HudElementModule {
    private final NumberValue scale = add(new NumberValue("Scale", 1, .5, 2, .1));
    private final NumberValue radius = add(new NumberValue("Corner Radius", 3, 0, 14, .5));
    private final ColorValue background = add(new ColorValue("Background Color", 0x5A0F0F0F));
    private final ColorValue slotColor = add(new ColorValue("Slot Color", 0));
    private final BooleanValue showCount = add(new BooleanValue("Show Count", true));
    private final ModeValue anchor = add(new ModeValue("Anchor", "BOTTOM_RIGHT", "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"));
    private final String[] names = new String[27], counts = new String[27];
    private final int[] amounts = new int[27];
    private volatile boolean available;
    private final HudElement element = new HudElement() {
        public String id() { return name(); }
        public HudAnchor anchor() { return HudAnchor.valueOf(anchor.get()); }
        public float width(OverlayDraw d) { return available ? 9 * 12 * scale.asFloat() + 8 : 0; }
        public float height(OverlayDraw d) { return available ? 3 * 12 * scale.asFloat() + 2 : 0; }
        public void draw(OverlayDraw d, float x, float y) {
            if (!available) return;
            float s = scale.asFloat(), cell = 12 * s;
            d.roundedRect(x, y, width(d), height(d), radius.asFloat() * s, background.argb());
            for (int i = 0; i < 27; i++) {
                float sx = x + (i % 9) * (cell + 1), sy = y + (i / 9) * (cell + 1);
                d.rect(sx, sy, cell, cell, slotColor.argb());
                String text = names[i];
                if (text == null) continue;
                d.text(text, sx + 1, sy + 1, 7 * s, 0xFFFFFFFF);
                if (showCount.get() && counts[i] != null) {
                    float size = 6 * s;
                    d.text(counts[i], sx + cell - d.textWidth(counts[i], size), sy + cell - size, size, 0xFFFFFFFF);
                }
            }
        }
    };
    public InventoryElementModule() { super("Inventory"); }
    @Override protected HudElement element() { return element; }
    @Override protected void onHudStop() { available = false; }
    @Override public void onTick() {
        NocturneClient client = NocturneClient.get();
        GameBridge bridge = client == null ? null : client.gameBridge();
        if (bridge == null || bridge.inventory() == null) { available = false; return; }
        for (int i = 0; i < 27; i++) {
            Object stack = bridge.inventoryStack(i + 9);
            if (bridge.isEmptyStack(stack)) { names[i] = null; counts[i] = null; amounts[i] = 0; continue; }
            String full = bridge.itemDisplayName(stack);
            if (full == null || full.isEmpty()) names[i] = null;
            else {
                int end = full.offsetByCodePoints(0, Math.min(2, full.codePointCount(0, full.length())));
                String old = names[i];
                if (old == null || old.length() != end || !full.regionMatches(0, old, 0, end)) names[i] = full.substring(0, end);
            }
            int count = bridge.itemCount(stack);
            if (count != amounts[i] || counts[i] == null) { amounts[i] = count; counts[i] = count > 0 ? Integer.toString(count) : null; }
        }
        available = true;
    }
}
