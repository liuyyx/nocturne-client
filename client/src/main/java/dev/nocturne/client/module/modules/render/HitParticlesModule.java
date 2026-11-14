package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.concurrent.ThreadLocalRandom;

/** 受击贴图降级为投影文字；缺少受伤计时字段时不制造虚假的受击效果。 */
public final class HitParticlesModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final ModeValue mode = add(new ModeValue("Mode", "Stars", "Stars", "Hearts", "Bloom"));
    private final ModeValue physics = add(new ModeValue("Physics", "Fall", "Fall", "Fly"));
    private final ModeValue colorMode = add(new ModeValue("Color Mode", "Custom", "Custom", "Sync"));
    private final ColorValue color = add(new ColorValue("Color", 0x35FFFFFF));
    private final BooleanValue onlySelf = add(new BooleanValue("Only Self", false));
    private final NumberValue amount = add(new NumberValue("Amount", 2, 1, 5, 1));
    private final NumberValue lifeTime = add(new NumberValue("Life Time", 2, 1, 10, 1));
    private final NumberValue speed = add(new NumberValue("Speed", 2, 1, 20, 1));
    private final NumberValue scale = add(new NumberValue("Scale", 3, 1, 10, .1));
    private final Particle[] particles = new Particle[256];
    private final double[] position = new double[3];
    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();
    private GameBridge tickBridge;
    private Object world;
    private int next;

    public HitParticlesModule() {
        super("Hit Particles", Category.RENDER);
        for (int i = 0; i < particles.length; i++) particles[i] = new Particle();
    }

    @Override protected void onStart() { listen(WorldChangeEvent.class, event -> clear()); }
    @Override protected void onStop() { clear(); }

    private void clear() {
        for (Particle particle : particles) particle.born = 0;
        next = 0;
        world = null;
        tickBridge = null;
    }

    @Override public void onTick() {
        GameBridge bridge = bridge();
        Object current = bridge == null ? null : bridge.world();
        if (current != world) { clear(); world = current; }
        if (current == null) return;
        tickBridge = bridge;
        RenderSupport.forEachEntity(bridge, this);
        tickBridge = null;
    }

    @Override public void visit(Object entity) {
        GameBridge bridge = tickBridge;
        if (bridge == null || !bridge.isInstance(entity, ClassType.LIVING_ENTITY)
                || (onlySelf.get() && entity != bridge.player())) return;
        Object maximum = bridge.readField(entity, ClassType.LIVING_ENTITY, "hurtDuration");
        if (!(maximum instanceof Number) || ((Number) maximum).intValue() <= 1
                || bridge.hurtTime(entity) != ((Number) maximum).intValue() - 1
                || !bridge.entityPosition(entity, position)) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double height = Math.max(0, bridge.entityHeight(entity));
        double velocity = speed.get() * .1;
        long now = System.nanoTime();
        for (int i = 0, count = (int) amount.get().doubleValue(); i < count; i++) {
            Particle p = particles[next];
            next = (next + 1) % particles.length;
            p.x = position[0]; p.y = position[1] + height * (.25 + random.nextDouble() * .5); p.z = position[2];
            p.vx = (random.nextDouble() - .5) * velocity;
            p.vy = random.nextDouble() * velocity;
            p.vz = (random.nextDouble() - .5) * velocity;
            p.phase = random.nextDouble(); p.born = now;
            p.fall = physics.is("Fall");
        }
    }

    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        long now = System.nanoTime();
        String glyph = mode.is("Hearts") ? "❤" : mode.is("Bloom") ? "✧" : "✦";
        double life = lifeTime.get();
        int base = color.argb();
        for (Particle p : particles) {
            if (p.born == 0) continue;
            double t = (now - p.born) / 1_000_000_000.0;
            if (t < 0 || t >= life) { p.born = 0; continue; }
            double wave = Math.sin(t * 3 + p.phase * Math.PI * 2) * .08;
            projection.project(p.x + p.vx * t + wave,
                    p.y + p.vy * t - (p.fall ? .02 * t * t : 0),
                    p.z + p.vz * t + wave, point);
            if (!point.visible) continue;
            float fade = (float) (1 - t / life);
            int argb = colorMode.is("Sync")
                    ? RenderSupport.hsv(Math.round(((base >>> 24) & 255) * fade),
                            (float) (t * .5 + p.phase), 1, 1)
                    : RenderSupport.withAlpha(base, fade);
            draw.text(glyph, point.x, point.y, (float) (scale.get() * 2), argb);
        }
    }

    private static final class Particle {
        double x, y, z, vx, vy, vz, phase;
        long born;
        boolean fall;
    }
}
