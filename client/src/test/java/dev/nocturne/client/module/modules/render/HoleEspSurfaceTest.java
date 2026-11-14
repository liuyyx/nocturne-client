package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldProjection;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 洞扫描记录的是空气格，但默认着色必须落在真实支撑块或顶棚块。 */
class HoleEspSurfaceTest {
    @Test void safeHolePaintsItsFloorBlockNotTheAirAboveIt() throws Exception {
        assertHorizontalFace(HoleScan.SAFE, 3, 0.5, 5, 0.5, 30, 3);
    }
    @Test void airMarkerPaintsTheSolidCeilingNotTheAirBelowIt() throws Exception {
        assertHorizontalFace(HoleScan.AIR, 1, 0.5, 0, 0.5, -30, 2);
    }
    @Test void wallMarkerStartsAtTheBottomOfTheThreeRealBedrockBlocks() throws Exception {
        assertHorizontalFace(HoleScan.WALL, 1, 0.5, 5, 0.5, 30, 3);
    }

    private static void assertHorizontalFace(int type, int y, double eyeX, double eyeY,
                                              double eyeZ, float pitch, int surfaceY) throws Exception {
        HoleEspModule module = new HoleEspModule();
        Field snapshots = HoleEspModule.class.getDeclaredField("snapshots");
        snapshots.setAccessible(true);
        snapshots.set(module, Collections.singletonList(new int[]{0, y, -3, 1, -2, type}));
        List<float[]> quads = new ArrayList<float[]>();
        OverlayDraw draw = (OverlayDraw) Proxy.newProxyInstance(OverlayDraw.class.getClassLoader(),
                new Class<?>[]{OverlayDraw.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("quad")) {
                        float[] points = new float[8];
                        for (int i = 0; i < 8; i++) points[i] = ((Number) arguments[i]).floatValue();
                        quads.add(points);
                    }
                    if (method.getReturnType() == int.class) return 200;
                    if (method.getReturnType() == float.class) return 0f;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        WorldProjection projection = new WorldProjection();
        projection.update(eyeX, eyeY, eyeZ, 180, pitch, 70, 200, 200);
        module.drawWorldOverlay(draw, projection);
        WorldProjection.ScreenPoint corner = new WorldProjection.ScreenPoint();
        projection.project(0, surfaceY, -3, corner);
        assertTrue(quads.stream().anyMatch(quad -> contains(quad, corner.rawX, corner.rawY)),
                "填色必须经过实体方块表面投影角，不能整体上浮/下沉一个空气格");
    }
    private static boolean contains(float[] quad, float x, float y) {
        for (int i = 0; i < 8; i += 2) {
            if (Math.abs(quad[i] - x) < .001f && Math.abs(quad[i + 1] - y) < .001f) return true;
        }
        return false;
    }
}
