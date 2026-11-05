package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.util.ArrayList;
import java.util.List;

/** MasEffects：命中/冲击/护盾/死亡粒子、珍珠轨迹、自定义 Hitbox 与滑翔图标。 */
public final class MasEffectsModule extends GameModule implements WorldOverlay {
    private final BooleanValue windParticles = add(new BooleanValue("Wind Particles", true));
    private final BooleanValue customTotem = add(new BooleanValue("Custom Totem Effect", true));
    private final NumberValue totemOpacity = add(new NumberValue("Totem Effect Opacity", 1, 0, 1, 0.05));
    private final BooleanValue playerDeath = add(new BooleanValue("Player Death Effect", true));
    private final BooleanValue pearlTrail = add(new BooleanValue("Pearl Trail Particles", true));
    private final NumberValue pearlOpacity = add(new NumberValue("Pearl Trail Opacity", 0.5, 0, 1, 0.05));
    private final BooleanValue glideIcon = add(new BooleanValue("Glide Icon", true));
    private final BooleanValue maceShockwave = add(new BooleanValue("Mace Shockwave", true));
    private final NumberValue shockwaveSize = add(new NumberValue("Shockwave Size", 1, 0.1, 3, 0.1));
    private final NumberValue shockwaveOpacity = add(new NumberValue("Shockwave Opacity", 1, 0, 1, 0.05));
    private final BooleanValue maceSpark = add(new BooleanValue("Mace Spark", true));
    private final BooleanValue maceFlash = add(new BooleanValue("Mace Flash", true));
    private final BooleanValue shieldEffect = add(new BooleanValue("Shield Effect", true));
    private final BooleanValue armorParticles = add(new BooleanValue("Armor Particles", true));
    private final BooleanValue legacyShockwave = add(new BooleanValue("Legacy Mace Shockwave", false));
    private final BooleanValue customHitbox = add(new BooleanValue("Custom Hitbox", true));
    private final BooleanValue pearlColors = add(new BooleanValue("Pearl Hitbox Colors", true));
    private final TextValue pearlAllies = add(new TextValue("Pearl Allies", "", 512));
    private final ColorValue selfPearl = add(new ColorValue("Self Pearl Color", 0xB3FFFF00));
    private final ColorValue allyPearl = add(new ColorValue("Ally Pearl Color", 0xB30000FF));
    private final ColorValue otherPearl = add(new ColorValue("Other Pearl Color", 0xB3FF0000));
    private final NumberValue mobOpacity = add(new NumberValue("Mob Hitbox Opacity", 0.3, 0, 1, 0.05));
    private final NumberValue playerOpacity = add(new NumberValue("Player Hitbox Opacity", 1, 0, 1, 0.05));
    private final NumberValue hitboxFade = add(new NumberValue("Hitbox Fade Distance", 15, 0, 64, 1));
    private final NumberValue projectileFade = add(new NumberValue("Projectile Fade Distance", 5, 0, 64, 1));
    private final List<ParticleOverlay.Particle> particles = new ArrayList<ParticleOverlay.Particle>();

    public MasEffectsModule() {
        super("Mas Effects", Category.RENDER);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, event -> {
            if (event.post) {
                return;
            }
            double[] pos = bridge().entityPosition(event.target);
            if (pos == null) {
                return;
            }
            if (maceShockwave.get()) {
                emit(pos, legacyShockwave.get() ? "○" : "◎", shockwaveSize.get(), shockwaveOpacity.get());
            }
            if (maceSpark.get()) {
                emit(pos, "✦", 1.0, 1.0);
            }
            if (maceFlash.get()) {
                emit(pos, "ϟ", 1.0, 1.0);
            }
            if (shieldEffect.get() && bridge().usingItem(event.target)) {
                emit(pos, "◈", 1.0, 1.0);
            }
            if (armorParticles.get()) {
                emit(pos, "◆", 0.5, 0.8);
            }
            if (windParticles.get()) {
                emit(pos, "≈", 0.5, 0.5);
            }
            if (playerDeath.get() && bridge().health(event.target) <= 1.0f) {
                emit(pos, "☠", 2.0, 1.0);
            }
            if (customTotem.get()) {
                emit(pos, "♜", 1.0, totemOpacity.get());
            }
        });
    }

    private void emit(double[] pos, String glyph, double speed, double opacity) {
        ParticleOverlay.emit(particles, pos[0], pos[1] + 1.0, pos[2], speed, 1.2,
                0xFFFFFFFF & 0xFFFFFF | (int) (255 * opacity) << 24, glyph, "Fly");
    }

    @Override
    public void onTick() {
        if (pearlTrail.get()) {
            RenderSupport.forEachEntity(bridge(), entity -> {
                if (bridge().isInstance(entity, ClassType.THROWN_ENDER_PEARL)) {
                    double[] pos = bridge().entityPosition(entity);
                    if (pos != null) {
                        ParticleOverlay.emit(particles, pos[0], pos[1], pos[2], 0.1, 0.8,
                                0xFFFFFFB3 & 0xFFFFFF | (int) (255 * pearlOpacity.get()) << 24,
                                "•", "Fly");
                    }
                }
            });
        }
        ParticleOverlay.tick(particles);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        ParticleOverlay.draw(particles, draw, projection, 2.0f);
        if (customHitbox.get()) {
            RenderSupport.forEachEntity(bridge(), entity -> {
                double distance = bridge().distance(bridge().player(), entity);
                if (entity == bridge().player()) {
                    return;
                }
                if (bridge().isInstance(entity, ClassType.THROWN_ENDER_PEARL) && distance <= projectileFade.get()) {
                    WorldGeometry.entity(bridge(), draw, projection, entity, 1.0f, pearlColor(entity));
                } else if (distance <= hitboxFade.get()) {
                    float opacity = bridge().isInstance(entity, ClassType.PLAYER)
                            ? playerOpacity.asFloat() : mobOpacity.asFloat();
                    WorldGeometry.entity(bridge(), draw, projection, entity, 1.0f,
                            0xFFFFFF & 0xFFFFFF | (int) (255 * opacity) << 24);
                }
            });
        }
        if (glideIcon.get() && Boolean.TRUE.equals(bridge().callMapped(bridge().player(),
                ClassType.LIVING_ENTITY, "isFallFlying"))) {
            draw.text("⇈", draw.width() * 0.5f, draw.height() * 0.65f, 0xFFFFFFFF);
        }
    }

    private int pearlColor(Object entity) {
        if (!pearlColors.get()) {
            return 0xFFFFFFFF;
        }
        Object owner = bridge().callMapped(entity, ClassType.THROWABLE_PROJECTILE, "getOwner");
        if (owner == bridge().player()) {
            return selfPearl.argb();
        }
        String name = bridge().entityName(owner);
        for (String ally : pearlAllies.get().split("[,;\\s]+")) {
            if (!ally.isEmpty() && name.equalsIgnoreCase(ally)) {
                return allyPearl.argb();
            }
        }
        return otherPearl.argb();
    }

    @Override
    protected void onStop() {
        particles.clear();
    }
}
