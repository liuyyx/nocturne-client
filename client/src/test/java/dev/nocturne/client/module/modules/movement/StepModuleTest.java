package dev.nocturne.client.module.modules.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 高度必须按 ceil(height) 检查所有占用层，不能只看 y+1。 */
class StepModuleTest {

    @Test
    void layerCountFollowsCeiledHeight() {
        assertEquals(1, StepModule.requiredLayers(1.0));
        assertEquals(2, StepModule.requiredLayers(1.5));
        assertEquals(2, StepModule.requiredLayers(2.0));
        assertEquals(3, StepModule.requiredLayers(2.5));
    }

    @Test
    void blockedSecondLayerRejectsTheStep() {
        assertFalse(StepModule.clearAbove(2.0, layer -> layer != 2));
        assertFalse(StepModule.clearAbove(1.5, layer -> layer == 1));
    }

    @Test
    void allRequiredLayersMustBeAir() {
        assertTrue(StepModule.clearAbove(2.0, layer -> true));
        assertTrue(StepModule.clearAbove(1.0, layer -> true));
        assertFalse(StepModule.clearAbove(2.5, layer -> layer < 3));
    }
}
