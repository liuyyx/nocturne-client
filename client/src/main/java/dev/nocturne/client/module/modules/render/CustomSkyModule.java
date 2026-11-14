package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 上游降级：只绘制屏幕顶部天空带，不替换天空、地平线或真实着色器。 */
public final class CustomSkyModule extends GameModule implements WorldOverlay {
    private final ModeValue shader = add(new ModeValue("Shader", "Cine", "Cine", "Euabe", "Local"));
    private final NumberValue speed = add(new NumberValue("Speed", 4, 0, 20, .1));
    private final ColorValue color = add(new ColorValue("Color", 0xC8FFFFFF));
    private final ColorValue background = add(new ColorValue("Background Color", 0xFF000000));
    private double phase;
    private boolean warned;

    public CustomSkyModule() { super("CustomSky", Category.RENDER); }

    @Override public void onTick() { phase = (phase + speed.get() * .025) % (Math.PI * 2); }

    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!draw.reliableAlphaBlend()) {
            if (!warned) {
                warned = true;
                System.out.println("[nocturne] CustomSky: alpha blend unavailable on this backend");
            }
            return;
        }
        float w = draw.width(), h = draw.height() * .3f;
        if (w <= 0 || h <= 0) return;
        int base = color.argb();
        if (shader.is("Cine")) {
            float pulse = (float) (.65 + .35 * Math.sin(phase));
            for (int i = 0; i < 12; i++) {
                float factor = pulse * (1f - .7f * i / 11f);
                int shade = RenderSupport.blend(base & 0xFF000000, base, factor);
                draw.rect(0, h * i / 12f, w, h * (i + 1) / 12f - h * i / 12f + .5f, shade);
            }
        } else if (shader.is("Euabe")) {
            int alternate = RenderSupport.blend(base, 0xFF463A96, .55f);
            int shift = (int) (phase * 12 / (Math.PI * 2));
            for (int i = 0; i < 12; i++) {
                draw.rect(0, h * i / 12f, w, h / 12f + .5f, ((i + shift) & 1) == 0 ? base : alternate);
            }
        } else {
            draw.rect(0, 0, w, h, background.argb());
            float spacing = 48f, shift = (float) (phase / (Math.PI * 2) * spacing);
            for (float x = -h - spacing + shift; x < w; x += spacing) {
                float topLeft = Math.max(0f, x), topRight = Math.min(w, x + 12f);
                float bottomLeft = Math.max(0f, x + h), bottomRight = Math.min(w, x + h + 12f);
                if (topLeft < w && bottomRight > 0f) {
                    draw.quad(Math.min(w, topLeft), 0, Math.max(0f, topRight), 0,
                            Math.max(0f, bottomRight), h, Math.min(w, bottomLeft), h, base);
                }
            }
        }
    }
}
