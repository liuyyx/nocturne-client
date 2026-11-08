package dev.nocturne.client.module.modules.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Throwpot 喝汤 tick 门槛与空队列关闭时机。 */
class ThrowpotModuleTest {

    @Test
    void soupWaitsForThirtyTwoTicks() {
        assertFalse(ThrowpotModule.soupDone(0, false, 0));
        assertFalse(ThrowpotModule.soupDone(1, false, 1));
        assertFalse(ThrowpotModule.soupDone(31, true, 31));
        assertTrue(ThrowpotModule.soupDone(32, false, 32));
    }

    /** 使用进度从有到无说明喝完了，提前结束等待。 */
    @Test
    void soupFinishingEarlyCountsAsDone() {
        assertTrue(ThrowpotModule.soupDone(0, true, 35));
    }

    /** 读不到使用进度时用超时兜底，避免永远停在等待状态。 */
    @Test
    void missingProgressFallsBackToTimeout() {
        assertTrue(ThrowpotModule.soupDone(0, false, 60));
    }

    @Test
    void emptyQueueDoesNotCloseOnTheFirstTick() {
        assertFalse(ThrowpotModule.closeOnEmpty(1));
        assertTrue(ThrowpotModule.closeOnEmpty(2));
    }
}
