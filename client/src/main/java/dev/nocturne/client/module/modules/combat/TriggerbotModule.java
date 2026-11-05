package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.NumberValue;

/** Trigger Bot：随机延迟、攻击冷却与暴击下落窗口。 */
public final class TriggerbotModule extends GameModule {
    private final NumberValue range = add(new NumberValue("Range", 3, 1, 7, 0.1));
    private final NumberValue delayMin = add(new NumberValue("Random Delay Min", 2, 0, 20, 1));
    private final NumberValue delayMax = add(new NumberValue("Random Delay Max", 13, 0, 20, 1));
    private final NumberValue attackCooldown = add(new NumberValue("Attack Cooldown", 0.9, 0.5, 1, 0.05));
    private final NumberValue critFallDistance = add(new NumberValue("Crit Fall Distance", 0, 0, 1, 0.01));
    private long nextAttack;

    public TriggerbotModule() {
        super("Trigger Bot", Category.COMBAT);
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now < nextAttack || state().attackStrengthScale() < attackCooldown.get()) {
            return;
        }
        float fall = bridge().fallDistance(bridge().player());
        if (fall >= 1.0f && fall <= 1.14f && critFallDistance.get() > 0.0f
                && fall < critFallDistance.get()) {
            return;
        }
        Object target = bridge().hitEntity(actions().pick(range.get(), 1.0f));
        if (target != null && actions().attack(target)) {
            nextAttack = now + EpsilonSupport.randomInt(delayMin.asInt(), delayMax.asInt()) * 50L;
        }
    }
}
