package dev.nocturne.client.module.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trigger Bot 的暴击窗口方向与受伤无敌帧门。
 *
 * <p>回归：旧条件 {@code fall < critFallDistance} 在 {@code fall >= 1.0} 的固定区间里恒为假，
 * 设置项形同虚设；且没有 hurtTime 判定，会在无敌帧里连续空打。
 */
class TriggerbotWindowTest {

    @Test
    void critWindowRequiresFallingPastTheConfiguredDistance() {
        // 设置关闭：不限制下落。
        assertFalse(TriggerbotModule.critWindowBlocked(0.0f, true, 0.0));
        assertFalse(TriggerbotModule.critWindowBlocked(3.0f, false, 0.0));

        // 设置 0.7：还没落够 → 等；落够且离地 → 允许。
        assertTrue(TriggerbotModule.critWindowBlocked(0.3f, false, 0.7));
        assertFalse(TriggerbotModule.critWindowBlocked(0.9f, false, 0.7));

        // 站在地上永远进不了暴击窗口（无论下落距离显示多少）。
        assertTrue(TriggerbotModule.critWindowBlocked(5.0f, true, 0.7));
    }

    @Test
    void hurtTimeBlocksRepeatedAttacks() {
        assertTrue(TriggerbotModule.targetReady(0));
        assertFalse(TriggerbotModule.targetReady(1));
        assertFalse(TriggerbotModule.targetReady(10));
    }
}
