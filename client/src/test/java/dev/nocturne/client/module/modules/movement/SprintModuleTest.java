package dev.nocturne.client.module.modules.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 强制条件与 Cancel Invis。 */
class SprintModuleTest {

    @Test
    void forcesSprintOnlyWhenMovingAndNotBlocked() {
        assertTrue(SprintModule.shouldForceSprint(false, false, true));
        assertFalse(SprintModule.shouldForceSprint(true, false, true));
        assertFalse(SprintModule.shouldForceSprint(false, true, true));
        assertFalse(SprintModule.shouldForceSprint(false, false, false));
    }
}
