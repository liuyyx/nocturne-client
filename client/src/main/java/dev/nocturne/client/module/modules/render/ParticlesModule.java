package dev.nocturne.client.module.modules.render;

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
import java.util.List;

/** Particles：萤火与环境 SnowFlake/Stars/Hearts/Dollars/Bloom 粒子。 */
public final class ParticlesModule extends GameModule implements WorldOverlay {
    private final BooleanValue fireFlies = add(new BooleanValue("Fire Flies", true));
    private final NumberValue ffCount = add(new NumberValue("FF Count", 30, 20, 200, 1));
    private final NumberValue ffSize = add(new NumberValue("FF Size", 1, 0.1, 2, 0.1));
    private final ModeValue mode = add(new ModeValue("Mode", "SnowFlake", "Off", "SnowFlake", "Stars", "Hearts", "Dollars", "Bloom"));
    private final NumberValue count = add(new NumberValue("Count", 100, 20, 800, 10));
    private final NumberValue size = add(new NumberValue("Size", 1, 0.1, 6, 0.1));
    private final ModeValue colorMode = add(new ModeValue("Color Mode", "Sync", "Custom", "Sync"));
    private final ColorValue color = add(new ColorValue("Color", 0xFF3786FA));
    private final ModeValue physics = add(new ModeValue("Physics", "Fly", "Drop", "Fly"));
    private final List<ParticleOverlay.Particle> particles = new ArrayList<ParticleOverlay.Particle>();

    public ParticlesModule() {
        super("Particles", Category.RENDER);
    }

    @Override
    public void onTick() {
        int target = (fireFlies.get() ? ffCount.asInt() : 0) + (mode.is("Off") ? 0 : count.asInt());
        while (particles.size() < target) {
            boolean firefly = fireFlies.get() && particles.size() < ffCount.asInt();
            String glyph = firefly ? "•" : glyph();
            int particleColor = colorMode.is("Sync") ? ParticleOverlay.rainbow(particles.size() * 31L) : color.argb();
            double angle = Math.random() * Math.PI * 2.0;
            double radius = 2.0 + Math.random() * 8.0;
            ParticleOverlay.emit(particles, state().x() + Math.cos(angle) * radius,
                    state().y() + Math.random() * 3.0, state().z() + Math.sin(angle) * radius,
                    0.3, 4.0, particleColor, glyph, firefly ? "Fly" : physics.get());
        }
        ParticleOverlay.tick(particles);
    }

    private String glyph() {
        if (mode.is("Stars")) return "★";
        if (mode.is("Hearts")) return "♥";
        if (mode.is("Dollars")) return "$";
        if (mode.is("Bloom")) return "✦";
        return "❄";
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        ParticleOverlay.draw(particles, draw, projection, Math.max(size.asFloat(), ffSize.asFloat()));
    }

    @Override
    protected void onStop() {
        particles.clear();
    }
}
