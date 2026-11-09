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
        // 暴击窗口：设置 >0 时只在下落达到该距离且不在地面时攻击。旧实现的比较方向写反了
        // （fall < 阈值），条件恒不成立，`Crit Fall Distance` 形同虚设。
        float fall = bridge().fallDistance(bridge().player());
        if (critWindowBlocked(fall, state().onGround(), critFallDistance.get())) {
            return;
        }
        Object target = bridge().hitEntity(actions().pick(range.get(), 1.0f));
        if (target == null || !targetReady(bridge().hurtTime(target))) {
            // 受伤无敌帧内再打不会结算伤害，只会白耗攻击冷却与蓄力。
            return;
        }
        if (actions().attack(target)) {
            nextAttack = now + EpsilonSupport.randomInt(delayMin.asInt(), delayMax.asInt()) * 50L;
        }
    }

    /**
     * 暴击窗口是否还没到。
     *
     * <p>设置 >0 时：必须已经离地且下落距离达到该阈值（暴击需要 fallDistance &gt; 0 且不在
     * 地面），否则先不打。包级可见以便单测钉住比较方向。
     */
    static boolean critWindowBlocked(float fallDistance, boolean onGround, double critFallDistance) {
        return critFallDistance > 0.0
                && (onGround || fallDistance < critFallDistance);
    }

    /** 受伤无敌帧内攻击不会结算伤害，只在严格无 hurtTime 时放行。 */
    static boolean targetReady(int hurtTime) {
        return hurtTime <= 0;
    }
}
