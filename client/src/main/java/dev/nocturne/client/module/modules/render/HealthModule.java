package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;

import java.util.Locale;

/** 准星下方生命值与吸收值；上游心形 sprite 改成文字，数值变化时才重建字符串。 */
public final class HealthModule extends GameModule implements WorldOverlay {
    private String text;
    private int healthTenths = Integer.MIN_VALUE;
    private int absorptionTenths = Integer.MIN_VALUE;
    private float ratio;
    private int age;
    private int lastHurt;
    private int hurtAt = -100;

    public HealthModule() { super("Health", Category.RENDER); }

    @Override
    protected void onStop() {
        text = null;
        healthTenths = absorptionTenths = Integer.MIN_VALUE;
        age = lastHurt = 0;
        hurtAt = -100;
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        Object player = bridge == null ? null : bridge.player();
        if (player == null) { text = null; return; }
        float health = bridge.health(player), maximum = bridge.maxHealth(player);
        Object amount = bridge.callMapped(player, ClassType.LIVING_ENTITY, "getAbsorptionAmount");
        float absorption = amount instanceof Number ? ((Number) amount).floatValue() : 0f;
        int nextHealth = Math.round(health * 10f), nextAbsorption = Math.round(absorption * 10f);
        if (text == null || nextHealth != healthTenths || nextAbsorption != absorptionTenths) {
            healthTenths = nextHealth;
            absorptionTenths = nextAbsorption;
            text = "♥ " + String.format(Locale.ROOT, "%.1f", health * .5f)
                    + (absorption > 0f ? " + " + String.format(Locale.ROOT, "%.1f", absorption * .5f) : "");
        }
        ratio = maximum <= 0f ? 0f : Math.max(0f, Math.min(1f, health / maximum));
        int hurt = bridge.hurtTime(player);
        if (hurt > lastHurt) hurtAt = age;
        lastHurt = hurt;
        age++;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (text == null) return;
        int color = ratio < .5f ? RenderSupport.blend(0xFFFF5555, 0xFFFFFF55, ratio * 2f)
                : RenderSupport.blend(0xFFFFFF55, 0xFF55FF55, (ratio - .5f) * 2f);
        if (age - hurtAt < 20 && (age & 2) == 0) color = RenderSupport.withAlpha(color, .35f);
        float jitter = ratio < .3f ? ((age & 1) == 0 ? -1f : 1f) : 0f;
        draw.text(text, (draw.width() - draw.textWidth(text, 10f)) * .5f + jitter,
                draw.height() * .5f + 30f, 10f, color);
    }
}
