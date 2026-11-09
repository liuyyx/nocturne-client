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

    /** 攻击门：请求是否被接受 + 当前请求角是否已收敛到目标容差内。 */
    @Test
    void reportsRequestAcceptanceAndAimConvergence() {
        RotationController controller = new RotationController();

        assertTrue(controller.request(30.0f, 0.0f, 5, 1_000_000_000L));
        assertTrue(controller.isAimedAt(32.0f, 4.0f), "within tolerance");
        assertFalse(controller.isAimedAt(38.0f, 4.0f), "outside tolerance");
        assertEquals(30.0f, controller.activeYaw(), 0.001f);

        // 低优先级请求被未过期的高优先级请求抢占：返回 false，视角保持原请求。
        assertFalse(controller.request(80.0f, 0.0f, 4, 1_000_000_000L));
        assertEquals(30.0f, controller.activeYaw(), 0.001f);
        assertFalse(controller.isAimedAt(80.0f, 4.0f));

        // 更高优先级接管后，旧目标自然不再“对准”。
        assertTrue(controller.request(80.0f, 0.0f, 9, 1_000_000_000L));
        assertTrue(controller.isAimedAt(80.0f, 4.0f));
        controller.release(9);
        assertFalse(controller.isAimedAt(80.0f, 4.0f));
    }

    /** 无有效请求（过期/被释放）时不能报告“已对准”。 */
    @Test
    void expiredRequestIsNeverConsideredAimed() throws Exception {
        RotationController controller = new RotationController();
        assertTrue(controller.request(45.0f, 0.0f, 5, 1_000_000L));
        Thread.sleep(20L);
        assertFalse(controller.isAimedAt(45.0f, 5.0f));
        assertTrue(Float.isNaN(controller.activeYaw()));
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
