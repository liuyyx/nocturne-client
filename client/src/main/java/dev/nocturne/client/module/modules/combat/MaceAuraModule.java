package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Mace Aura：Normal/Mace、分段 VClip、Paper 包、冷却与目标过滤。 */
public final class MaceAuraModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Mace", "Normal", "Mace"));
    private final NumberValue range = add(new NumberValue("Range", 3, 1, 6, 0.1));
    private final NumberValue moveDistance = add(new NumberValue("Move Distance", 8, 1, 20, 0.5));
    private final BooleanValue paperServer = add(new BooleanValue("Paper Server", true));
    private final NumberValue vClip = add(new NumberValue("VClip", 10, 1, 512, 1));
    private final BooleanValue damageVClip = add(new BooleanValue("Damage VClip", true));
    private final NumberValue overrideVClip = add(new NumberValue("Override VClip", 30, 1, 512, 1));
    private final BooleanValue swingHand = add(new BooleanValue("Swing Hand", false));
    private final BooleanValue cooldown = add(new BooleanValue("Cooldown", true));
    private final NumberValue cooldownBase = add(new NumberValue("Cooldown Base", 0.75, 0.1, 1, 0.05));
    private final NumberValue attackDelay = add(new NumberValue("Attack Delay", 50, 1, 2000, 1));
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue animals = add(new BooleanValue("Animals", false));
    private final BooleanValue mobs = add(new BooleanValue("Mobs", false));
    private final BooleanValue villagers = add(new BooleanValue("Villagers", false));
    private long nextAttack;

    public MaceAuraModule() {
        super("Mace Aura", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (EpsilonSupport.enabled("Elytra Combat") || System.currentTimeMillis() < nextAttack) {
            return;
        }
        TargetManager.Target target = client().targets().find(client(), EpsilonSupport.targets(range.get(),
                players.get(), animals.get() || mobs.get() || villagers.get(), true, TargetManager.Sort.DISTANCE));
        if (target == null || cooldown.get() && state().attackStrengthScale() < cooldownBase.get()) {
            return;
        }
        if (mode.is("Mace")) {
            int mace = EpsilonSupport.findHotbar(bridge(), "mace");
            if (mace >= 0) {
                actions().selectSlot(mace, true);
            }
        }
        if (paperServer.get()) {
            for (int i = 0; i < 4; i++) {
                actions().sendGround(state().onGround());
            }
        }
        double height = damageVClip.get() ? overrideVClip.get() : vClip.get();
        actions().sendPositionRotation(state().x(), state().y() + Math.min(moveDistance.get(), height),
                state().z(), state().yaw(), state().pitch(), false);
        EpsilonSupport.aim(bridge(), state(), target.x(), target.aimY(), target.z(), 180, 8);
        if (actions().attack(target.entity())) {
            actions().swing(false, !swingHand.get());
            nextAttack = System.currentTimeMillis() + attackDelay.asInt();
        }
        actions().sendPositionRotation(state().x(), state().y(), state().z(), state().yaw(), state().pitch(), state().onGround());
    }
}
