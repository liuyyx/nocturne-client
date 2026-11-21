package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldProjection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 水晶盒的底部必须与实体脚部坐标一致，而不是向下再偏移一个半径。 */
class CrystalChamsGeometryTest {
    @Test
    void crystalBoxStartsAtFeet() throws Exception {
        CrystalChamsModule module = new CrystalChamsModule();
        List<double[]> positions = new ArrayList<double[]>();
        positions.add(new double[]{0.0, 64.0, 8.0});
        Field positionsField = CrystalChamsModule.class.getDeclaredField("positions");
        positionsField.setAccessible(true);
        positionsField.set(module, positions);
        Field count = CrystalChamsModule.class.getDeclaredField("count");
        count.setAccessible(true);
        count.setInt(module, 1);
        Field generation = CrystalChamsModule.class.getDeclaredField("publishedGeneration");
        generation.setAccessible(true);
        generation.setInt(module, 0);

        WorldProjection projection = new WorldProjection();
        projection.update(0.0, 64.0, 0.0, 0.0f, 0.0f, 70.0f, 800, 600);
        final List<Float> endpoints = new ArrayList<Float>();
        OverlayDraw draw = (OverlayDraw) java.lang.reflect.Proxy.newProxyInstance(
                OverlayDraw.class.getClassLoader(), new Class<?>[]{OverlayDraw.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("line")) {
                        endpoints.add(((Number) args[1]).floatValue());
                        endpoints.add(((Number) args[3]).floatValue());
                    }
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == float.class) return 0f;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        module.drawWorldOverlay(draw, projection);

        WorldProjection.ScreenPoint feet = new WorldProjection.ScreenPoint();
        WorldProjection.ScreenPoint top = new WorldProjection.ScreenPoint();
        projection.project(0.0, 64.0, 8.0, feet);
        projection.project(0.0, 64.0 + 1.96, 8.0, top);
        assertTrue(endpoints.contains(feet.y), "底部端点必须来自实体脚部 Y");
        assertTrue(endpoints.contains(top.y), "顶部端点必须保留约两格盒高");
    }
}
