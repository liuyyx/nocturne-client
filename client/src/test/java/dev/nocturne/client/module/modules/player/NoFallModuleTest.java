package dev.nocturne.client.module.modules.player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NoFall 纯触发条件。 */
class NoFallModuleTest {

    @Test
    void overridesOnlyWhileFallingAndUnprotected() {
        assertTrue(NoFallModule.shouldOverride(false, false, false, 3.0, -0.5));
        assertFalse(NoFallModule.shouldOverride(true, false, false, 3.0, -0.5));
        assertFalse(NoFallModule.shouldOverride(false, true, false, 3.0, -0.5));
        assertFalse(NoFallModule.shouldOverride(false, false, true, 3.0, -0.5));
        assertFalse(NoFallModule.shouldOverride(false, false, false, 2.0, -0.5));
        assertFalse(NoFallModule.shouldOverride(false, false, false, 3.0, 0.2));
    }
}
