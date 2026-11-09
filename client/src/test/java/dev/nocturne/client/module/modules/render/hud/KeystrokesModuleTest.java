package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.runtime.PhysicalKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Keystrokes 的显示格式与物理键快照。 */
class KeystrokesModuleTest {

    @AfterEach
    void clear() {
        PhysicalKeys.clear();
    }

    @Test
    void showsPressedKeysAndCps() {
        assertEquals("W a s d  3/1 CPS  [SPACE]",
                KeystrokesModule.formatLine(true, false, false, false, true, true, 3, 1, false));
    }

    @Test
    void cpsOnlySkipsKeyCaps() {
        assertEquals("4 / 2 CPS",
                KeystrokesModule.formatLine(false, false, false, false, false, true, 4, 2, true));
    }

    @Test
    void physicalSnapshotUsesMovementOrder() {
        PhysicalKeys.update(true, false, true, false, true);
        assertArrayEquals(new boolean[]{true, false, true, false, true}, PhysicalKeys.movement());
    }
}
