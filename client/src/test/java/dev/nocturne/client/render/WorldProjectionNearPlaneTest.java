package dev.nocturne.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 近平面裁剪与视口 clamp 的回归测试。
 *
 * <p>贴住箱子时 AABB 的某个角会落在近平面内侧；旧实现直接丢弃该角，结果框要么消失、要么被
 * 剩下几个极远的角撑满屏幕。现在把点沿视线插值到近平面再投影，并把屏幕坐标 clamp 到视口。
 */
class WorldProjectionNearPlaneTest {

    private static WorldProjection projection() {
        WorldProjection projection = new WorldProjection();
        // 眼位原点、朝 +Z、视场 70°、视口 800×600。
        projection.update(0d, 0d, 0d, 0f, 0f, 70f, 800, 600);
        return projection;
    }

    @Test
    void pointInsideTheNearPlaneStaysOnScreen() {
        WorldProjection projection = projection();
        WorldProjection.ScreenPoint point = projection.project(0.01, 0d, 0.05d);
        assertTrue(point.visible, "a point just in front of the camera must not be dropped");
        assertTrue(point.x >= 0f && point.x <= 800f, "x must be clamped into the viewport: " + point.x);
        assertTrue(point.y >= 0f && point.y <= 600f, "y must be clamped into the viewport: " + point.y);
        assertTrue(point.x > 350f && point.x < 450f, "near-plane interpolation must stay near the centre");
    }

    @Test
    void pointBehindTheCameraIsStillRejected() {
        WorldProjection projection = projection();
        assertFalse(projection.project(0d, 0d, -1d).visible,
                "points behind the camera would flip to the other side");
    }

    @Test
    void nearCornersNeverExceedTheViewport() {
        WorldProjection projection = projection();
        for (double x = -0.4; x <= 0.4; x += 0.1) {
            for (double y = -0.4; y <= 0.4; y += 0.1) {
                WorldProjection.ScreenPoint point = projection.project(x, y, 0.02d);
                if (!point.visible) {
                    continue;
                }
                assertTrue(point.x >= 0f && point.x <= 800f, "x out of viewport: " + point.x);
                assertTrue(point.y >= 0f && point.y <= 600f, "y out of viewport: " + point.y);
            }
        }
    }
}
