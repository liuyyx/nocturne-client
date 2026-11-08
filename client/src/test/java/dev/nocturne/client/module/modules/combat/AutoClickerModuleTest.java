package dev.nocturne.client.module.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Auto Clicker CPS 必须落在 20Hz tick 能兑现的 1..20 范围内。 */
class AutoClickerModuleTest {

    private static final double EPSILON = 1.0E-9;

    @Test
    void configuredMaximumMatchesTheTickRate() {
        assertEquals(20.0, AutoClickerModule.MAX_CPS, EPSILON);
        assertEquals(20.0, AutoClickerModule.clampCps(20.0), EPSILON);
    }

    @Test
    void burstMultiplierCannotExceedTickRate() {
        assertEquals(20.0, AutoClickerModule.clampCps(12.0 * 3.0), EPSILON);
    }

    @Test
    void normalCpsIsUnchangedAndFloorIsOne() {
        assertEquals(12.0, AutoClickerModule.clampCps(12.0), EPSILON);
        assertEquals(1.0, AutoClickerModule.clampCps(0.2), EPSILON);
    }
}
