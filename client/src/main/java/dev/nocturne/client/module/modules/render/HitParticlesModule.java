package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** HitParticles：Stars/Hearts/Bloom、Fall/Fly 与 Custom/Sync 颜色的实体命中粒子。 */
public final class HitParticlesModule extends GameModule implements WorldOverlay {
    private final ModeValue mode = add(new ModeValue("Mode", "Stars", "Stars", "Hearts", "Bloom"));
    private final ModeValue physics = add(new ModeValue("Physics", "Fall", "Fall", "Fly"));
    private final ModeValue colorMode = add(new ModeValue("Color Mode", "Sync", "Custom", "Sync"));
    private final ColorValue color = add(new ColorValue("Color", 0x35FFFFFF));
    private final BooleanValue onlySelf = add(new BooleanValue("Only Self", false));
    private final NumberValue amount = add(new NumberValue("Amount", 2, 1, 5, 1));
    private final NumberValue lifeTime = add(new NumberValue("Life Time", 2, 1, 10, 1));
    private final NumberValue speed = add(new NumberValue("Speed", 2, 1, 20, 1));
    private final NumberValue scale = add(new NumberValue("Scale", 3, 1, 10, 0.1));
    private final List<ParticleOverlay.Particle> particles = new ArrayList<ParticleOverlay.Particle>();
    private final Map<Integer, Integer> hurt = new HashMap<Integer, Integer>();

    public HitParticlesModule() {
        super("Hit Particles", Category.RENDER);
    }

    @Override
    public void onTick() {
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (!bridge().isInstance(entity, ClassType.LIVING_ENTITY) || onlySelf.get() && entity != bridge().player()) {
                return;
            }
            int id = bridge().entityId(entity);
            int current = bridge().hurtTime(entity);
            int previous = hurt.containsKey(id) ? hurt.get(id) : 0;
            hurt.put(id, current);
            if (current <= previous || current <= 0) {
                return;
            }
            double[] pos = bridge().entityPosition(entity);
            if (pos == null) {
                return;
            }
            int particleColor = colorMode.is("Sync") ? ParticleOverlay.rainbow(id * 17L) : color.argb();
            String glyph = mode.is("Hearts") ? "♥" : mode.is("Bloom") ? "✦" : "★";
            for (int i = 0; i < amount.asInt(); i++) {
                ParticleOverlay.emit(particles, pos[0], pos[1] + 1.0, pos[2], speed.get(), lifeTime.get(),
                        particleColor, glyph, physics.get());
            }
        });
        ParticleOverlay.tick(particles);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        ParticleOverlay.draw(particles, draw, projection, scale.asFloat());
    }

    @Override
    protected void onStop() {
        particles.clear();
        hurt.clear();
    }
}
