package dev.nocturne.client.module.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Velocity 击退解码、水中抵消与爆炸分支不得伪造零速度。 */
class VelocityModuleTest {

    private static final double EPSILON = 1.0E-9;

    @Test
    void decodesVelocityPacketWithTheEightThousandScale() {
        assertArrayEquals(new double[]{1.0, -1.0, 0.5},
                VelocityModule.decodeMotion(new int[]{42, 8000, -8000, 4000}), EPSILON);
    }

    /** No Water Push 不再受 pending 守卫限制，且只清零水平分量。 */
    @Test
    void waterPushCancelsHorizontalButKeepsVerticalMotion() {
        assertArrayEquals(new double[]{0.0, 0.42, 0.0}, VelocityModule.waterMotion(0.42), EPSILON);
    }

    /** 爆炸包没有速度字段：Delay/Reduce 放行原始击退，只有 Cancel 取消。 */
    @Test
    void delayAndReduceNeverCancelExplosions() {
        assertFalse(VelocityModule.cancelExplosion("Delay"));
        assertFalse(VelocityModule.cancelExplosion("Reduce"));
        assertFalse(VelocityModule.cancelExplosion("JumpReset"));
        assertTrue(VelocityModule.cancelExplosion("Cancel"));
    }
}
