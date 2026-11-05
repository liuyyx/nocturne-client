package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** SpearKill：长矛蓄力后按方向或上方位置执行激流突进并攻击。 */
public final class SpearKillModule extends GameModule {
    private final ModeValue lungeMode = add(new ModeValue("Lunge Mode", "DirectionBased", "DirectionBased", "Above"));
    private final NumberValue maxRange = add(new NumberValue("Max Range", 6, 1, 16, 0.1));
    private final NumberValue lungeStrength = add(new NumberValue("Lunge Strength", 3.7, 0.5, 15, 0.1));
    private final NumberValue aboveHeight = add(new NumberValue("Above Height", 10, 3, 30, 0.5));
    private final NumberValue aboveDistance = add(new NumberValue("Above Height Distance", 3, 1, 10, 0.5));
    private final NumberValue stopDistance = add(new NumberValue("Stop Distance", 0.6, 0, 10, 0.1));
    private final NumberValue chargeTime = add(new NumberValue("Charge Time", 100, 0, 100, 1));
    private final BooleanValue stopOnTarget = add(new BooleanValue("Stop On Target", true));
    private final BooleanValue autoSwitch = add(new BooleanValue("Auto Switch", true));
    private final BooleanValue rotate = add(new BooleanValue("Rotate", true));
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue mobs = add(new BooleanValue("Mobs", false));
    private final BooleanValue animals = add(new BooleanValue("Animals", false));
    private final BooleanValue villagers = add(new BooleanValue("Villagers", false));
    private final BooleanValue invisible = add(new BooleanValue("Invisible", true));
    private int charging;
    private int original = -1;

    public SpearKillModule() {
        super("Spear Kill", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (EpsilonSupport.enabled("ElytraCombat")) {
            return;
        }
        TargetManager.Target target = client().targets().find(client(), EpsilonSupport.targets(maxRange.get(),
                players.get(), mobs.get() || animals.get() || villagers.get(), invisible.get(),
                TargetManager.Sort.DISTANCE));
        if (target == null) {
            reset();
            return;
        }
        if (rotate.get()) {
            EpsilonSupport.aim(bridge(), state(), target.x(), target.aimY(), target.z(), 180, 6);
        }
        if (charging == 0) {
            int slot = EpsilonSupport.findHotbar(bridge(), "spear", "trident");
            if (slot < 0) {
                return;
            }
            original = state().selectedSlot();
            if (autoSwitch.get()) {
                actions().selectSlot(slot, false);
            }
            actions().useItem();
            charging = 1;
            return;
        }
        charging++;
        if (charging < Math.max(1, chargeTime.asInt() / 5)) {
            return;
        }
        double dx = target.x() - state().x();
        double dz = target.z() - state().z();
        double distance = Math.hypot(dx, dz);
        if (distance <= stopDistance.get()) {
            if (stopOnTarget.get()) {
                actions().setMotion(0.0, 0.0, 0.0);
            }
        } else {
            double strength = lungeStrength.get();
            double y = lungeMode.is("Above") && target.distance() >= aboveDistance.get()
                    ? aboveHeight.get() * 0.1 : 0.2;
            actions().setMotion(dx / distance * strength, y, dz / distance * strength);
        }
        if (target.distance() <= Math.max(3.0, stopDistance.get())) {
            actions().attack(target.entity());
            actions().swing(false, false);
            reset();
        }
    }

    private void reset() {
        if (original >= 0 && autoSwitch.get() && actions() != null) {
            actions().selectSlot(original, false);
        }
        original = -1;
        charging = 0;
    }

    @Override
    protected void onStop() {
        reset();
    }
}
