package dev.nocturne.client.module.modules.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AutoTool 的最佳槽选择与并列行为。 */
class AutoToolModuleTest {

    @Test
    void picksHighestDestroySpeed() {
        double[] scores = {1.0, 8.0, 3.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0};
        assertEquals(1, AutoToolModule.bestSlot(scores, 2));
    }

    @Test
    void keepsCurrentSlotWhenItIsBestOrAllEmpty() {
        assertEquals(2, AutoToolModule.bestSlot(
                new double[]{0.0, 0.0, 9.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0}, 2));
        assertEquals(4, AutoToolModule.bestSlot(new double[9], 4));
    }

    @Test
    void tiesKeepTheEarlierHigherSlot() {
        double[] scores = {0.0, 5.0, 5.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
        assertEquals(1, AutoToolModule.bestSlot(scores, 0));
    }
}
