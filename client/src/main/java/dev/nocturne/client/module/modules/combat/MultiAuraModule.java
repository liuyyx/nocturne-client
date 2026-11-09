package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.List;

/** Multi Aura：按随机 CPS 对本轮全部合法目标各执行一次真实攻击。 */
public final class MultiAuraModule extends GameModule {
    private final NumberValue range = add(new NumberValue("Range", 5, 1, 6, 0.05));
    private final NumberValue minCps = add(new NumberValue("Min CPS", 10, 1, 20, 1));
    private final NumberValue maxCps = add(new NumberValue("Max CPS", 12, 1, 20, 1));
    private final NumberValue fov = add(new NumberValue("FOV", 360, 30, 360, 10));
    private final BooleanValue swingHand = add(new BooleanValue("Swing Hand", true));
    private final BooleanValue pauseContainers = add(new BooleanValue("Pause On Containers", false));
    private final BooleanValue player = add(new BooleanValue("Player", true));
    private final BooleanValue mob = add(new BooleanValue("Mob", true));
    private final BooleanValue animal = add(new BooleanValue("Animal", true));
    private final BooleanValue villager = add(new BooleanValue("Villager", false));
    private final BooleanValue ambient = add(new BooleanValue("Ambient", false));
    private final BooleanValue water = add(new BooleanValue("Water", false));
    private final BooleanValue others = add(new BooleanValue("Others", false));
    private final BooleanValue invisible = add(new BooleanValue("Invisible", true));
    private double attacks;

    public MultiAuraModule() {
        super("Multi Aura", Category.COMBAT);
    }

    @Override
    protected void onStop() {
        attacks = 0.0;
    }

    @Override
    public void onTick() {
        if (pauseContainers.get() && bridge().currentScreen() != null || bridge().usingItem(bridge().player())) {
            return;
        }
        TargetManager.Options options = EpsilonSupport.targets(range.get(), player.get(),
                mob.get() || animal.get() || villager.get() || ambient.get() || water.get() || others.get(),
                invisible.get(), TargetManager.Sort.DISTANCE);
        options.maxAngle = fov.get();
        List<TargetManager.Target> targets = client().targets().list(client(), options);
        double gain = EpsilonSupport.randomInt(minCps.asInt(), maxCps.asInt()) / 20.0;
        // 配额：有目标才累积且封顶 1；没目标立即清零。旧实现无上限累积，空场地挂机 60s 后
        // 一进圈 while 循环会一次性打出几十次攻击（瞬间爆发，服务端直接判定异常）。
        attacks = quotaAfterTick(attacks, gain, !targets.isEmpty());
        if (!canConsume(attacks)) {
            return;
        }
        boolean hit = false;
        for (TargetManager.Target target : targets) {
            if (actions().attack(target.entity())) {
                hit = true;
            }
        }
        if (!hit) {
            // 全部被 HitSelect/其它订阅者取消：本 tick 不扣配额，下一次再尝试。
            return;
        }
        attacks -= 1.0;   // 每 tick 最多消费 1 份配额
        if (swingHand.get()) {
            actions().swing(false, false);
        }
    }

    /**
     * 单 tick 配额演化：无目标归零，有目标封顶 {@code 1.0}（每 tick 最多一次）。
     *
     * <p>包级可见以便单测直接验证“空目标 60s 后不爆发”。
     */
    static double quotaAfterTick(double current, double gain, boolean hasTargets) {
        if (!hasTargets) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, current + gain));
    }

    /** 配额是否足够消费一次攻击。 */
    static boolean canConsume(double quota) {
        return quota >= 1.0;
    }
}
