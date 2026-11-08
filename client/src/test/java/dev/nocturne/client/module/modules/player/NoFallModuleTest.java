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

    /** GrimJump 必须交替按/松跳跃键：只置 true 会让跳跃键永久按住。 */
    @Test
    void grimJumpAlternatesPressAndRelease() {
        assertTrue(NoFallModule.grimJumpPressed(0));
        assertFalse(NoFallModule.grimJumpPressed(1));
        assertTrue(NoFallModule.grimJumpPressed(2));
        assertFalse(NoFallModule.grimJumpPressed(3));
    }
}
