package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldProjection;

import java.util.Iterator;
import java.util.List;

/** 轻量世界粒子池：真实速度/重力/寿命与投影 billboard 文本图形。 */
public final class ParticleOverlay {
    private ParticleOverlay() {
    }

    public static void emit(List<Particle> particles, double x, double y, double z, double speed,
                            double lifeSeconds, int color, String glyph, String physics) {
        double angle = Math.random() * Math.PI * 2.0;
        double vertical = Math.random() * 0.8 + 0.2;
        particles.add(new Particle(x, y, z, Math.cos(angle) * speed * 0.1,
                vertical * speed * 0.1, Math.sin(angle) * speed * 0.1,
                System.currentTimeMillis() + (long) (lifeSeconds * 1000.0), color, glyph, physics));
    }

    public static void tick(List<Particle> particles) {
        long now = System.currentTimeMillis();
        Iterator<Particle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            Particle particle = iterator.next();
            if (now >= particle.expires) {
                iterator.remove();
                continue;
            }
            particle.x += particle.motionX;
            particle.y += particle.motionY;
            particle.z += particle.motionZ;
            if (particle.physics.equals("Fall") || particle.physics.equals("Drop")) {
                particle.motionY -= 0.035;
                particle.motionX *= 0.995;
                particle.motionZ *= 0.995;
            } else {
                particle.motionX *= 1.005;
                particle.motionZ *= 1.005;
            }
        }
    }

    public static void draw(List<Particle> particles, OverlayDraw draw, WorldProjection projection, float scale) {
        long now = System.currentTimeMillis();
        for (Particle particle : particles) {
            WorldProjection.ScreenPoint point = projection.project(particle.x, particle.y, particle.z);
            if (!point.visible) {
                continue;
            }
            float remaining = Math.max(0.0f, Math.min(1.0f,
                    (particle.expires - now) / 1000.0f));
            int alpha = Math.min(255, (int) ((particle.color >>> 24) * remaining));
            int color = particle.color & 0xFFFFFF | alpha << 24;
            float size = Math.max(5.0f, scale * 12.0f / (float) Math.max(1.0, point.depth));
            draw.text(particle.glyph, point.x, point.y, size, color);
        }
    }

    public static int rainbow(long offset) {
        return 0xFF000000 | java.awt.Color.HSBtoRGB((System.currentTimeMillis() + offset) % 1000L / 1000.0f,
                0.85f, 1.0f);
    }

    public static final class Particle {
        private double x;
        private double y;
        private double z;
        private double motionX;
        private double motionY;
        private double motionZ;
        private final long expires;
        private final int color;
        private final String glyph;
        private final String physics;

        private Particle(double x, double y, double z, double motionX, double motionY, double motionZ,
                         long expires, int color, String glyph, String physics) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.motionX = motionX;
            this.motionY = motionY;
            this.motionZ = motionZ;
            this.expires = expires;
            this.color = color;
            this.glyph = glyph;
            this.physics = physics;
        }
    }
}
