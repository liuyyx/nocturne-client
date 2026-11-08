package dev.nocturne.client.module.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Criticals 在攻击前置就退疾跑，不等下一次移动输入。 */
class CriticalsModuleTest {

    @Test
    void preAttackStopsSprintImmediately() {
        assertTrue(CriticalsModule.shouldSuppressSprint(true, false));
    }

    @Test
    void postAttackAndIdleTicksDoNotTouchSprint() {
        assertFalse(CriticalsModule.shouldSuppressSprint(true, true));
        assertFalse(CriticalsModule.shouldSuppressSprint(false, false));
        assertFalse(CriticalsModule.shouldSuppressSprint(false, true));
    }
}
