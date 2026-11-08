package dev.nocturne.client.module.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reach 只在目标超出原版距离时替换准星命中。 */
class ReachModuleTest {

    @Test
    void extendsOnlyBeyondVanillaReach() {
        assertTrue(ReachModule.shouldExtend(3.5, 3.0));
        assertFalse(ReachModule.shouldExtend(3.0, 3.0));
        assertFalse(ReachModule.shouldExtend(2.9, 3.0));
    }

    @Test
    void configuredMinimumShiftsTheThreshold() {
        assertTrue(ReachModule.shouldExtend(3.2, 3.1));
        assertFalse(ReachModule.shouldExtend(3.05, 3.1));
    }
}
