package dev.nocturne.client.module.modules.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Flight 水平速度：斜向输入必须归一化，不能变成 √2 倍。 */
class FlyModuleTest {

    private static final double EPSILON = 1.0E-9;

    @Test
    void diagonalInputKeepsTheConfiguredSpeed() {
        double speed = 3.5;
        // yaw 0：W=+Z；W+D 同时按下 → forward=1, strafe=1
        double[] diagonal = FlyModule.moveVector(1.0f, 1.0f, 0.0, speed);
        assertEquals(speed, Math.hypot(diagonal[0], diagonal[1]), EPSILON);
    }

    @Test
    void straightInputIsUnchanged() {
        double speed = 2.0;
        double[] forward = FlyModule.moveVector(1.0f, 0.0f, 0.0, speed);
        assertEquals(0.0, forward[0], EPSILON);
        assertEquals(speed, forward[1], EPSILON);

        double[] straightBack = FlyModule.moveVector(-1.0f, 0.0f, 0.0, speed);
        assertEquals(-speed, straightBack[1], EPSILON);
    }

    @Test
    void partialInputStaysProportional() {
        double half = Math.hypot(FlyModule.moveVector(0.5f, 0.0f, 0.0, 4.0)[0],
                FlyModule.moveVector(0.5f, 0.0f, 0.0, 4.0)[1]);
        assertEquals(2.0, half, EPSILON, "half input must not be scaled up to full speed");
    }

    @Test
    void yawRotatesTheVector() {
        double[] east = FlyModule.moveVector(1.0f, 0.0f, -90.0, 1.0);
        // yaw -90 → 面向 +X
        assertEquals(1.0, east[0], EPSILON);
        assertTrue(Math.abs(east[1]) < EPSILON);
    }
}
