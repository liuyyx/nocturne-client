package dev.nocturne.client.module.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi Aura 配额：无目标清零、有目标封顶 1、每 tick 最多消费一次。
 *
 * <p>回归：旧实现把 CPS 无上限累加到 {@code attacks}，挂机 60s 后一进目标范围就会在同一个
 * tick 里 while 循环攻击几十次（服务端直接判定异常）。
 */
class MultiAuraQuotaTest {

    @Test
    void idleTicksResetTheQuotaInsteadOfAccumulating() {
        double quota = 0.0;
        // 60s @ 20Hz：即使每 tick 都按满 CPS 蓄力，没有目标也必须归零。
        for (int tick = 0; tick < 1200; tick++) {
            quota = MultiAuraModule.quotaAfterTick(quota, 1.0, false);
        }
        assertEquals(0.0, quota, 0.0001, "no targets must clear the quota");

        // 目标进入范围的第一 tick 最多只有一份配额。
        quota = MultiAuraModule.quotaAfterTick(quota, 1.0, true);
        assertEquals(1.0, quota, 0.0001, "first target tick must not burst past one attack");
        assertTrue(MultiAuraModule.canConsume(quota));
    }

    @Test
    void quotaIsCappedAtOnePerTickEvenWithHighCps() {
        double quota = 0.0;
        int attacks = 0;
        for (int tick = 0; tick < 600; tick++) {
            // 40 CPS 的极端输入：每 tick 应仍只允许一次攻击。
            quota = MultiAuraModule.quotaAfterTick(quota, 2.0, true);
            if (MultiAuraModule.canConsume(quota)) {
                quota -= 1.0;
                attacks++;
            }
        }
        assertEquals(600, attacks, "one attack per tick at most");
    }

    /** 蓄力未满时不能消费；达到/超过 1 时按一次扣减。 */
    @Test
    void consumeRequiresAFullPoint() {
        assertFalse(MultiAuraModule.canConsume(0.99));
        assertTrue(MultiAuraModule.canConsume(1.0));
        // 越过 1 也只封顶到 1（不得攒出第二份配额）。
        assertEquals(1.0, MultiAuraModule.quotaAfterTick(0.999, 0.5, true), 0.0001);
        assertEquals(0.999, MultiAuraModule.quotaAfterTick(0.999, 0.0, true), 0.0001);
    }
}
