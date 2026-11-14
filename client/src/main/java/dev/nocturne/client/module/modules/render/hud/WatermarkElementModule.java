package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.hud.HudAnchor;
import dev.nocturne.client.hud.HudElement;
import dev.nocturne.client.module.HudElementModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.value.*;

/** 三种文字水印；切片和渐变使用绘制后端的裁剪区域。 */
public final class WatermarkElementModule extends HudElementModule {
    private final TextValue text = add(new TextValue("Text", "nocturne", 128));
    private final ModeValue style = add(new ModeValue("Style", "Tradition", "Tradition", "Glitch", "Sakura"));
    private final NumberValue scale = add(new NumberValue("Scale", 1, .5, 2, .1));
    private final ColorValue textColor = add(new ColorValue("Text Color", 0xEBFFFFFF));
    private final ModeValue colorMode = add(new ModeValue("Color Mode", "Single", "Single", "Double"));
    private final ColorValue color = add(new ColorValue("Color", 0xFFFFFFFF));
    private final ColorValue color1 = add(new ColorValue("Color 1", 0xFFFFFFFF));
    private final ColorValue color2 = add(new ColorValue("Color 2", 0xFFFF0000));
    private final ColorValue glitchColor = add(new ColorValue("Glitch Color", 0xFFFFFFFF));
    private final NumberValue chromaticX = add(new NumberValue("Chromatic X", 1.5, 0, 6, .1));
    private final NumberValue chromaticY = add(new NumberValue("Chromatic Y", 0, 0, 6, .1));
    private final NumberValue sliceHeight = add(new NumberValue("Slice Height", 4, 1, 32, .5));
    private final NumberValue sliceAmount = add(new NumberValue("Slice Amount", 2.5, 0, 10, .1));
    private final NumberValue glitchStrength = add(new NumberValue("Glitch Strength", .65, 0, 1, .05));
    private final NumberValue scanlineStrength = add(new NumberValue("Scanline Strength", .45, 0, 1, .05));
    private final NumberValue noiseStrength = add(new NumberValue("Noise Strength", .25, 0, 1, .05));
    private final ModeValue anchor = add(new ModeValue("Anchor", "TOP_LEFT", "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"));
    private final HudElement hud = new Panel();
    private String cached = text.get();
    public WatermarkElementModule() { super("Watermark"); }
    @Override protected HudElement element() { return hud; }
    @Override public void onTick() {
        String value = text.get();
        if (value.equals(cached)) return;
        cached = value;
    }
    private final class Panel implements HudElement {
        public String id() { return name(); }
        public HudAnchor anchor() { return HudAnchor.valueOf(anchor.get()); }
        private float size() { return 12 * scale.asFloat(); }
        public float width(OverlayDraw d) { return d.textWidth(cached, size()); }
        public float height(OverlayDraw d) { return d.textHeight(size()); }
        public void draw(OverlayDraw d, float x, float y) {
            float size = size(), w = width(d), h = height(d); long phase = System.currentTimeMillis() / 80;
            if (style.is("Sakura")) {
                y += (float) Math.sin(System.currentTimeMillis() / 700.0) * scale.asFloat();
                if (colorMode.is("Single")) d.text(cached, x, y, size, color.argb());
                else {
                    for (int i = 0; i < 8; i++) {
                        float left = x + w * i / 8, right = x + w * (i + 1) / 8;
                        d.pushClip(left, y, right - left, h);
                        try {
                            d.text(cached, x, y, size, RenderSupport.blend(color1.argb(), color2.argb(), i / 7f));
                        } finally { d.popClip(); }
                    }
                }
            } else if (style.is("Glitch")) {
                int seed = (int) (phase * 1103515245L + 12345);
                float slice = sliceHeight.asFloat() * scale.asFloat();
                float margin = sliceAmount.asFloat() * glitchStrength.asFloat();
                for (float top = 0; top < h; top += slice) {
                    seed = seed * 1664525 + 1013904223;
                    float shift = (((seed >>> 16) & 65535) / 32767.5f - 1) * margin;
                    d.pushClip(x - margin, y + top, w + 2 * margin, Math.min(slice, h - top));
                    try {
                        d.text(cached, x + shift, y, size, textColor.argb());
                    } finally { d.popClip(); }
                }
                d.text(cached, x + chromaticX.asFloat(), y + chromaticY.asFloat(), size,
                        RenderSupport.withAlpha(glitchColor.argb(), glitchStrength.asFloat()));
                int shade = ((int) (scanlineStrength.asFloat() * 128) << 24);
                for (int row = 0; row < (int) h; row += 2) d.rect(x, y + row, w, 1, shade);
                int dots = (int) (sliceAmount.asFloat() * 4); int noise = ((int) (noiseStrength.asFloat() * 255) << 24) | 0xFFFFFF;
                for (int i = 0; i < dots; i++) { seed = seed * 1664525 + 1013904223; float dx = ((seed >>> 16) & 65535) / 65535f * w; seed = seed * 1664525 + 1013904223; float dy = ((seed >>> 16) & 65535) / 65535f * h; d.rect(x + dx, y + dy, 1, 1, noise); }
            } else d.text(cached, x, y, size, textColor.argb());
        }
    }
}
