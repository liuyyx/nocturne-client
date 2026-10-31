package dev.nocturne.client.module.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KeepSprint Buffer Abuse 的计数/回落序列。 */
class KeepSprintBufferTest {

    @Test
    void disabledBufferAlwaysAllowsSlowdown() {
        KeepSprintModule.BufferGate gate = new KeepSprintModule.BufferGate();
        for (int i = 0; i < 10; i++) {
            assertTrue(gate.allow(false, 5.0, 2.0));
        }
    }

    @Test
    void enabledBufferAllowsBurstThenRejectsDuringDecay() {
        KeepSprintModule.BufferGate gate = new KeepSprintModule.BufferGate();
        for (int i = 0; i < 5; i++) {
            assertTrue(gate.allow(true, 5.0, 2.0), "burst " + i);
        }
        assertFalse(gate.allow(true, 5.0, 2.0), "combo reached max, first decay tick rejects");
        assertFalse(gate.allow(true, 5.0, 2.0));
        assertFalse(gate.allow(true, 5.0, 2.0));
        assertTrue(gate.allow(true, 5.0, 2.0), "after combo reaches zero the next hit is allowed");
    }

    @Test
    void resetClearsBurstState() {
        KeepSprintModule.BufferGate gate = new KeepSprintModule.BufferGate();
        for (int i = 0; i < 5; i++) {
            gate.allow(true, 5.0, 2.0);
        }
        assertFalse(gate.allow(true, 5.0, 2.0));
        gate.reset();
        assertTrue(gate.allow(true, 5.0, 2.0));
    }
}
