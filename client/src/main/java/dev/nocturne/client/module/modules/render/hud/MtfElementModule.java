package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.hud.HudAnchor;
import dev.nocturne.client.hud.HudElement;
import dev.nocturne.client.module.HudElementModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 旋转花瓣 HUD。上游降级：无法获取鱼饼纹理，使用三个文字花瓣近似。 */
public final class MtfElementModule extends HudElementModule {
    private final NumberValue size = add(new NumberValue("Size", 32, 12, 96, 1));
    private final NumberValue speed = add(new NumberValue("Speed", 160, -720, 720, 5));
    private final ColorValue color = add(new ColorValue("Color", 0xFFFFFFFF));
    private final ModeValue anchor = add(new ModeValue("Anchor", "CENTER", "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"));
    private final Element element = new Element();

    public MtfElementModule() { super("MTF"); }
    @Override protected HudElement element() { return element; }
    @Override protected void onHudStart() { element.lastNanos = 0; }

    private final class Element implements HudElement {
        private long lastNanos;
        private double phase;
        @Override public String id() { return name(); }
        @Override public HudAnchor anchor() { return HudAnchor.valueOf(anchor.get()); }
        @Override public float width(OverlayDraw draw) { return size.asFloat(); }
        @Override public float height(OverlayDraw draw) { return size.asFloat(); }
        @Override public void draw(OverlayDraw draw, float x, float y) {
            long now = System.nanoTime();
            if (lastNanos != 0) phase = (phase + (now - lastNanos) / 1000000000.0 * speed.asFloat() / 160.0) % (Math.PI * 2.0);
            lastNanos = now;
            float extent = size.asFloat();
            float font = extent * 0.5f;
            float centerX = x + extent * 0.5f;
            float centerY = y + extent * 0.5f;
            float radius = extent * 0.2f;
            float halfWidth = draw.textWidth("✿", font) * 0.5f;
            float halfHeight = draw.textHeight(font) * 0.5f;
            for (int i = 0; i < 3; i++) {
                double angle = phase + i * Math.PI * 2.0 / 3.0;
                draw.text("✿", centerX + (float) Math.cos(angle) * radius - halfWidth,
                        centerY + (float) Math.sin(angle) * radius - halfHeight, font, color.argb());
            }
        }
    }
}
