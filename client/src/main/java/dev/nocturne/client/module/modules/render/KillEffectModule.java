package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Kill Effect：目标消失时触发 Lightning/Explosion/Squid/Blood 本地视觉与声音。 */
public final class KillEffectModule extends GameModule implements WorldOverlay {
    private final BooleanValue lightning = add(new BooleanValue("Lightning", true));
    private final BooleanValue explosion = add(new BooleanValue("Explosion", true));
    private final BooleanValue squid = add(new BooleanValue("Squid", true));
    private final BooleanValue blood = add(new BooleanValue("Blood", true));
    private final Set<Object> pending = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
    private final List<ParticleOverlay.Particle> particles = new ArrayList<ParticleOverlay.Particle>();

    public KillEffectModule() {
        super("Kill Effect", Category.RENDER);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, event -> {
            if (!event.post) {
                pending.add(event.target);
            }
        });
    }

    @Override
    public void onTick() {
        Set<Integer> alive = new HashSet<Integer>();
        RenderSupport.forEachEntity(bridge(), entity -> alive.add(bridge().entityId(entity)));
        for (Object entity : pending) {
            if (alive.contains(bridge().entityId(entity)) && bridge().health(entity) > 0.0f) {
                continue;
            }
            double[] pos = bridge().entityPosition(entity);
            if (pos == null) {
                continue;
            }
            if (lightning.get()) {
                bridge().playSound("ENTITY_LIGHTNING_BOLT_THUNDER", 1.0f, 1.0f);
                emit(pos, "ϟ", 0xFFFFFFAA, 12);
            }
            if (explosion.get()) {
                bridge().playSound("ENTITY_FIREWORK_ROCKET_BLAST", 1.0f, 1.0f);
                emit(pos, "✦", 0xFFFFAA55, 9);
            }
            if (squid.get()) {
                emit(pos, "♣", 0xFF4444AA, 6);
            }
            if (blood.get()) {
                emit(pos, "•", 0xFFAA0000, 12);
            }
        }
        pending.clear();
        ParticleOverlay.tick(particles);
    }

    private void emit(double[] pos, String glyph, int color, int amount) {
        for (int i = 0; i < amount; i++) {
            ParticleOverlay.emit(particles, pos[0], pos[1] + 1.0, pos[2], 2.0, 1.5,
                    color, glyph, "Fall");
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        ParticleOverlay.draw(particles, draw, projection, 2.0f);
    }

    @Override
    protected void onStop() {
        pending.clear();
        particles.clear();
    }
}
