package dev.nocturne.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldProjection} 的数学口径测试。
 *
 * <p>投影是这套世界覆盖层的**唯一**几何来源：符号写反了不会报错，只会让所有框镜像到屏幕另一侧
 * 或者全挤在中心。而真机验证要进世界、还要人工看画面，代价高——所以先在纯数学层面把三件事钉死：
 * 正前方落在视口中心、相机背后的点必须判不可见、右侧/上方的点分别往右/往上走。
 *
 * <p>约定（与游戏一致）：yaw 0 = 朝南（+Z），pitch 正 = 向下；屏幕 y 向下增长。
 */
class WorldProjectionTest {

    /** 构造一份"朝南平视"的投影：眼位在原点，视口 427×240，垂直 FOV 70°。 */
    private static WorldProjection lookingSouth() {
        WorldProjection projection = new WorldProjection();
        projection.update(0d, 0d, 0d, 0f, 0f, 70f, 427, 240);
        return projection;
    }

    /** 正前方的点必须落在视口正中心。 */
    @Test
    void straightAheadLandsInViewportCentre() {
        WorldProjection projection = lookingSouth();
        assertTrue(projection.isReady());

        WorldProjection.ScreenPoint point = projection.project(0d, 0d, 10d);

        assertTrue(point.visible, "a point straight ahead must be visible");
        assertEquals(427 / 2f, point.x, 0.6f, "x must be the viewport centre");
        assertEquals(240 / 2f, point.y, 0.6f, "y must be the viewport centre");
        assertEquals(10d, point.depth, 1e-6, "depth is the camera-space forward distance");
    }

    /** 相机背后的点必须判不可见：否则会投影到屏幕另一侧，画出一堆镜像的框。 */
    @Test
    void behindTheCameraIsInvisible() {
        WorldProjection projection = lookingSouth();

        WorldProjection.ScreenPoint behind = projection.project(0d, 0d, -10d);
        assertFalse(behind.visible, "a point behind the camera must not be visible");
        assertFalse(projection.project(0d, 0d, 0.01d).visible,
                "a point inside the near plane must not be visible");
    }

    /** 右侧的点往右走、上方的点往上走（屏幕 y 向下，所以 y 变小）。 */
    @Test
    void rightAndUpMapToRightAndUp() {
        WorldProjection projection = lookingSouth();
        float centreX = 427 / 2f;
        float centreY = 240 / 2f;

        WorldProjection.ScreenPoint right = projection.project(5d, 0d, 10d);
        assertTrue(right.visible);
        assertTrue(right.x > centreX, "a point to the right must land right of centre; x=" + right.x);

        WorldProjection.ScreenPoint up = projection.project(0d, 5d, 10d);
        assertTrue(up.visible);
        assertTrue(up.y < centreY, "a point above must land above centre (screen y grows down); y=" + up.y);
    }

    /** 朝西（yaw=90）时，世界 -X 方向变成正前方，原先的前方（+Z）落到右侧。 */
    @Test
    void yawTurnsTheView() {
        WorldProjection projection = new WorldProjection();
        projection.update(0d, 0d, 0d, 90f, 0f, 70f, 427, 240);

        WorldProjection.ScreenPoint west = projection.project(-10d, 0d, 0d);
        assertTrue(west.visible, "facing west (yaw 90), -X must be in front");
        assertEquals(427 / 2f, west.x, 0.6f);
        assertEquals(240 / 2f, west.y, 0.6f);

        WorldProjection.ScreenPoint south = projection.project(-5d, 0d, 5d);
        assertTrue(south.visible, "a point 45 degrees off the new forward must stay on screen");
        assertTrue(south.x > 427 / 2f, "after turning west, the +Z side must be off to the right; x=" + south.x);
    }

    /** 超出视场角的点判不可见：正侧方（与视线成 90°）不该出现在屏幕上。 */
    @Test
    void pointsOutsideTheFovAreInvisible() {
        WorldProjection projection = lookingSouth();

        assertFalse(projection.project(10d, 0d, 0.5d).visible,
                "a point almost straight to the side is outside a 70-degree vertical FOV");
    }

    /** 视口未就绪（宽或高为 0）时一律不可见：拿零尺寸去投会得到 NaN。 */
    @Test
    void viewportMustBeReady() {
        WorldProjection projection = new WorldProjection();
        projection.update(0d, 0d, 0d, 0f, 0f, 70f, 0, 0);

        assertFalse(projection.isReady());
        assertFalse(projection.project(0d, 0d, 10d).visible);
    }
}
