package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.render.ParticleOverlay;
import dev.nocturne.client.module.modules.render.RenderSupport;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.List;

/** Epsilon Crystal Aura：独立放/炸节拍、概率、点击模拟、反虚弱与粒子。 */
public final class CrystalAuraModule extends GameModule implements WorldOverlay {
    private final KeyValue activate = add(new KeyValue("Activate Key", KeyValue.NONE));
    private final NumberValue placeDelay = add(new NumberValue("Place Delay", 0, 0, 20, 0.1));
    private final NumberValue breakDelay = add(new NumberValue("Break Delay", 0, 0, 20, 0.1));
    private final NumberValue placeChance = add(new NumberValue("Place Chance", 100, 0, 100, 0.1));
    private final NumberValue breakChance = add(new NumberValue("Break Chance", 100, 0, 100, 0.1));
    private final BooleanValue stopOnKill = add(new BooleanValue("Stop on Kill", false));
    private final BooleanValue fakePunch = add(new BooleanValue("Fake Punch", false));
    private final BooleanValue clickSimulation = add(new BooleanValue("Click Simulation", false));
    private final BooleanValue damageTick = add(new BooleanValue("Damage Tick", false));
    private final BooleanValue antiWeakness = add(new BooleanValue("Anti-Weakness", false));
    private final BooleanValue strictAir = add(new BooleanValue("Strict Air Check", false));
    private final NumberValue particleChance = add(new NumberValue("Particle Chance", 20, 0, 100, 0.1));
    private final BooleanValue swingHand = add(new BooleanValue("Swing Hand", true));
    private boolean held;
    private long nextPlace;
    private long nextBreak;
    private final List<ParticleOverlay.Particle> particles = new ArrayList<ParticleOverlay.Particle>();

    public CrystalAuraModule() {
        super("Crystal Aura", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(KeyEvent.class, event -> {
            if (activate.bound() && event.keyCode == activate.get()) held = event.pressed;
        });
        listen(MouseButtonEvent.class, event -> {
            if (activate.bound() && event.button == activate.get()) held = event.pressed;
        });
    }

    @Override
    public void onTick() {
        if (activate.bound() && !held) {
            return;
        }
        long now = System.currentTimeMillis();
        Object crystal = nearestCrystal();
        if (crystal != null && now >= nextBreak && chance(breakChance.get())
                && (!damageTick.get() || bridge().hurtTime(crystal) > 0)) {
            if (antiWeakness.get()) {
                int sword = EpsilonSupport.findHotbar(bridge(), "sword");
                if (sword >= 0) actions().selectSlot(sword, true);
            }
            if (actions().attack(crystal)) {
                if (clickSimulation.get()) actions().click();
                if (fakePunch.get() || swingHand.get()) actions().swing(false, fakePunch.get());
                if (chance(particleChance.get())) {
                    double[] pos = bridge().entityPosition(crystal);
                    if (pos != null) {
                        ParticleOverlay.emit(particles, pos[0], pos[1], pos[2], 2, 1,
                                0xFFFFB7C5, "✦", "Fly");
                    }
                }
                nextBreak = now + (long) (breakDelay.get() * 50.0);
            }
        }
        TargetManager.Target target = client().targets().find(client(), EpsilonSupport.targets(4.5,
                true, true, true, TargetManager.Sort.DISTANCE));
        if (target == null || now < nextPlace || !chance(placeChance.get())) {
            return;
        }
        if (stopOnKill.get() && bridge().health(target.entity()) <= 0.0f) {
            setEnabled(false);
            return;
        }
        int x = (int) Math.floor(target.x());
        int y = (int) Math.floor(target.y()) - 1;
        int z = (int) Math.floor(target.z());
        String base = bridge().blockDescription(bridge().blockStateAt(x, y, z));
        if (!(base.contains("obsidian") || base.contains("bedrock"))
                || !bridge().isAir(bridge().blockStateAt(x, y + 1, z))
                || strictAir.get() && !bridge().isAir(bridge().blockStateAt(x, y + 2, z))) {
            return;
        }
        int slot = EpsilonSupport.findHotbar(bridge(), "end_crystal");
        if (slot >= 0) {
            actions().selectSlot(slot, true);
            if (actions().placeAt(x, y, z, "up")) {
                if (swingHand.get()) actions().swing(false, false);
                nextPlace = now + (long) (placeDelay.get() * 50.0);
            }
        }
        ParticleOverlay.tick(particles);
    }

    private Object nearestCrystal() {
        final Object[] best = {null};
        final double[] distance = {4.5};
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (bridge().isInstance(entity, ClassType.END_CRYSTAL)) {
                double current = bridge().distance(bridge().player(), entity);
                if (current <= distance[0]) {
                    best[0] = entity;
                    distance[0] = current;
                }
            }
        });
        return best[0];
    }

    private static boolean chance(double value) {
        return EpsilonSupport.RANDOM.nextDouble() * 100.0 < value;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        ParticleOverlay.draw(particles, draw, projection, 2.0f);
    }

    @Override
    protected void onStop() {
        held = false;
        particles.clear();
    }
}
