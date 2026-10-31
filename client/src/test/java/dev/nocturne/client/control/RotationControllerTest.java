package dev.nocturne.client.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 旋转控制器的角度数学；不依赖游戏对象。 */
class RotationControllerTest {

    @Test
    void wrapsYawIntoHalfOpenRange() {
        assertEquals(0.0f, RotationController.wrap(360.0f), 0.001f);
        assertEquals(-10.0f, RotationController.wrap(350.0f), 0.001f);
        assertEquals(10.0f, RotationController.wrap(-350.0f), 0.001f);
        assertEquals(179.0f, RotationController.wrap(-181.0f), 0.001f);
    }

    @Test
    void derivesMinecraftAngles() {
        assertEquals(0.0f, RotationController.yawTo(0.0, 1.0), 0.001f);
        assertEquals(90.0f, RotationController.yawTo(-1.0, 0.0), 0.001f);
        assertEquals(-90.0f, RotationController.yawTo(1.0, 0.0), 0.001f);
        assertEquals(-45.0f, RotationController.pitchTo(1.0, 1.0), 0.001f);
        assertEquals(45.0f, RotationController.pitchTo(1.0, -1.0), 0.001f);
        assertEquals(-90.0f, RotationController.pitchTo(0.0, 2.0), 0.001f);
    }

    @Test
    void approachTakesShortestWayAndHonoursStep() {
        assertEquals(5.0f, RotationController.approach(0.0f, 5.0f, 10.0f), 0.001f);
        assertEquals(5.0f, RotationController.approach(0.0f, 30.0f, 5.0f), 0.001f);
        assertEquals(-5.0f, RotationController.approach(0.0f, -30.0f, 5.0f), 0.001f);
        assertEquals(179.0f, RotationController.approach(-179.0f, 179.0f, 5.0f), 0.001f);
        assertEquals(-179.0f, RotationController.approach(179.0f, -179.0f, 5.0f), 0.001f);
    }

    @Test
    void clampsPitchAndIgnoresInvalidRequests() {
        assertEquals(90.0f, RotationController.clampPitch(120.0f), 0.001f);
        assertEquals(-90.0f, RotationController.clampPitch(-120.0f), 0.001f);
        RotationController controller = new RotationController();
        controller.request(Float.NaN, 0.0f, 1, 1000L);
        assertFalse(controller.active());
        assertTrue(controller.yaw(12.0f) == 12.0f);
    }
}
