package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
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

/** 文字近似环境粒子与萤火残影；不使用上游贴图或原版粒子系统。 */
public final class ParticlesModule extends GameModule implements WorldOverlay {
    private final BooleanValue fireFlies = add(new BooleanValue("Fire Flies", true));
    private final NumberValue ffCount = add(new NumberValue("FF Count", 30, 20, 200, 1));
    private final NumberValue ffSize = add(new NumberValue("FF Size", 1, .1, 2, .1));
    private final ModeValue mode = add(new ModeValue("Mode", "SnowFlake", "Off", "SnowFlake", "Stars", "Hearts", "Dollars", "Bloom"));
    private final NumberValue count = add(new NumberValue("Count", 100, 20, 800, 1));
    private final NumberValue size = add(new NumberValue("Size", 1, .1, 6, .1));
    private final ModeValue colorMode = add(new ModeValue("Color Mode", "Custom", "Custom", "Sync"));
    private final ColorValue color = add(new ColorValue("Color", 0xFF37B1BA));
    private final ModeValue physics = add(new ModeValue("Physics", "Drop", "Drop", "Fly"));
    private final Particle[] flies = new Particle[200];
    private final Particle[] field = new Particle[800];
    private final Trail[] trails = new Trail[600];
    private final double[] playerPosition = new double[3];
    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();
    private Object world;
    private int tick, nextTrail;
    private boolean positionValid;

    public ParticlesModule() {
        super("Particles", Category.RENDER);
        for (int i = 0; i < flies.length; i++) flies[i] = new Particle();
        for (int i = 0; i < field.length; i++) field[i] = new Particle();
        for (int i = 0; i < trails.length; i++) trails[i] = new Trail();
    }

    @Override protected void onStart() { listen(WorldChangeEvent.class, event -> clear()); }
    @Override protected void onStop() { clear(); }

    private void clear() {
        for (Particle p : flies) p.active = false;
        for (Particle p : field) p.active = false;
        for (Trail trail : trails) trail.active = false;
        world = null; tick = 0; nextTrail = 0; positionValid = false;
    }

    @Override public void onTick() {
        GameBridge bridge = bridge();
        Object current = bridge == null ? null : bridge.world();
        if (world != current) { clear(); world = current; }
        if (current == null || !bridge.entityPosition(bridge.player(), playerPosition)) {
            positionValid = false;
            return;
        }
        positionValid = true;
        tick++;
        update(bridge, current, flies, fireFlies.get() ? (int) ffCount.get().doubleValue() : 0, true);
        update(bridge, current, field, mode.is("Off") ? 0 : (int) count.get().doubleValue(), false);
    }

    private void update(GameBridge bridge, Object current, Particle[] particles, int limit, boolean fly) {
        for (int i = 0; i < particles.length; i++) {
            Particle p = particles[i];
            if (i >= limit) { p.active = false; continue; }
            if (!p.active) reset(p);
            p.x += p.vx; p.y += p.vy; p.z += p.vz;
            if (physics.is("Drop")) p.vy -= .001;
            p.vx *= .9; p.vy *= .9; p.vz *= .9;
            p.age++;
            double dx = p.x - playerPosition[0], dy = p.y - playerPosition[1], dz = p.z - playerPosition[2];
            Object state = bridge.blockStateAt(current, (int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
            if (dx * dx + dy * dy + dz * dz > 16 || (state != null && !bridge.isAir(state))) reset(p);
            if (fly && tick % 3 == 0) {
                Trail trail = trails[nextTrail];
                nextTrail = (nextTrail + 1) % (Math.max(1, (int) ffCount.get().doubleValue()) * 3);
                trail.x = p.x; trail.y = p.y; trail.z = p.z;
                trail.born = tick; trail.active = true;
            }
        }
    }

    private void reset(Particle p) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // 球内采样保证初始化后不会立即因超过四格而重置。
        double angle = random.nextDouble() * Math.PI * 2;
        double radius = Math.sqrt(random.nextDouble()) * 3.4;
        p.x = playerPosition[0] + Math.cos(angle) * radius;
        p.y = playerPosition[1] + random.nextDouble() * 4 - 2;
        p.z = playerPosition[2] + Math.sin(angle) * radius;
        p.vx = (random.nextDouble() - .5) * .08;
        p.vy = (random.nextDouble() - .5) * .08;
        p.vz = (random.nextDouble() - .5) * .08;
        p.age = 0; p.active = true;
    }

    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!positionValid) return;
        int base = color.argb();
        if (colorMode.is("Sync")) base = RenderSupport.hsv((base >>> 24) & 255,
                (float) ((System.nanoTime() / 1_000_000_000.0 * .1) % 1), 1, 1);
        if (fireFlies.get()) {
            for (Trail trail : trails) {
                int age = tick - trail.born;
                if (!trail.active || age < 0 || age >= 9) continue;
                drawParticle(draw, projection, "✧", trail.x, trail.y, trail.z,
                        (float) ffSize.get().doubleValue(), base, (1f - age / 9f) * .45f);
            }
            drawParticles(draw, projection, flies, (int) ffCount.get().doubleValue(), "✧", (float) ffSize.get().doubleValue(), base);
        }
        if (!mode.is("Off")) {
            String glyph = mode.is("Stars") ? "✦" : mode.is("Hearts") ? "❤"
                    : mode.is("Dollars") ? "$" : mode.is("Bloom") ? "✿" : "❄";
            drawParticles(draw, projection, field, (int) count.get().doubleValue(), glyph, (float) size.get().doubleValue(), base);
        }
    }

    private void drawParticles(OverlayDraw draw, WorldProjection projection, Particle[] particles,
                               int limit, String glyph, float size, int color) {
        for (int i = 0; i < limit; i++) {
            Particle p = particles[i];
            if (p.active) drawParticle(draw, projection, glyph, p.x, p.y, p.z, size, color,
                    Math.min(1f, p.age / 20f));
        }
    }

    private void drawParticle(OverlayDraw draw, WorldProjection projection, String glyph,
                              double x, double y, double z, float size, int color, float alpha) {
        double dx = x - playerPosition[0], dy = y - playerPosition[1], dz = z - playerPosition[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > 4) alpha *= (float) Math.max(0, 1 - (distance - 4));
        if (alpha <= 0) return;
        projection.project(x, y, z, point);
        if (point.visible) draw.text(glyph, point.x, point.y, size, RenderSupport.withAlpha(color, alpha));
    }

    private static final class Particle {
        double x, y, z, vx, vy, vz;
        int age;
        boolean active;
    }

    private static final class Trail {
        double x, y, z;
        int born;
        boolean active;
    }
}
