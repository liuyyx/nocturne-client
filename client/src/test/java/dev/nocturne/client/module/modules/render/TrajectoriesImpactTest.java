package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldProjection;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 命中方块的三个轴向面都应投影为四个不同的角。 */
class TrajectoriesImpactTest {
    @Test
    void impactFacesUseAllFourCorners() throws Exception {
        TrajectoriesModule module = new TrajectoriesModule();
        Class<?> pathType = Class.forName(TrajectoriesModule.class.getName() + "$PathData");
        Constructor<?> constructor = pathType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object path = constructor.newInstance();
        Field axis = pathType.getDeclaredField("faceAxis");
        axis.setAccessible(true);
        Field z = pathType.getDeclaredField("impactZ");
        z.setAccessible(true);
        z.setDouble(path, 4.0);
        Method drawImpact = TrajectoriesModule.class.getDeclaredMethod("drawImpact",
                OverlayDraw.class, WorldProjection.class, pathType);
        drawImpact.setAccessible(true);
        final float[][] quad = new float[1][];
        OverlayDraw draw = (OverlayDraw) Proxy.newProxyInstance(OverlayDraw.class.getClassLoader(),
                new Class<?>[]{OverlayDraw.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("quad")) {
                        quad[0] = new float[8];
                        for (int i = 0; i < 8; i++) quad[0][i] = ((Number) arguments[i]).floatValue();
                    }
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == float.class) return 0f;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        WorldProjection projection = new WorldProjection();
        projection.update(2, 2, 0, 26.565f, 24.095f, 70, 800, 600);
        for (int face = 0; face < 3; face++) {
            axis.setInt(path, face);
            quad[0] = null;
            drawImpact.invoke(module, draw, projection, path);
            assertNotNull(quad[0], "面必须可见");
            float[] points = quad[0];
            double area = 0;
            for (int i = 0; i < 4; i++) {
                int next = (i + 1) % 4;
                area += points[2 * i] * points[2 * next + 1]
                        - points[2 * next] * points[2 * i + 1];
            }
            assertTrue(Math.abs(area) > 1.0, "面不应退化为线段或三角形");
            for (int i = 0; i < 4; i++) {
                for (int j = i + 1; j < 4; j++) {
                    assertTrue(Math.hypot(points[2 * i] - points[2 * j],
                            points[2 * i + 1] - points[2 * j + 1]) > 0.1,
                            "面必须有四个不同的角");
                }
            }
        }
    }

    @Test
    void trajectoryKeepsSubBlockPrecisionNearWorldBorder() throws Exception {
        TrajectoriesModule module = new TrajectoriesModule();
        Class<?> pathType = Class.forName(TrajectoriesModule.class.getName() + "$PathData");
        Constructor<?> constructor = pathType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object path = constructor.newInstance();
        Method append = TrajectoriesModule.class.getDeclaredMethod("append",
                pathType, double.class, double.class, double.class);
        append.setAccessible(true);
        append.invoke(null, path, 29999999.125, 0.0, 5.0);
        append.invoke(null, path, 29999999.875, 0.0, 5.0);
        Field paths = TrajectoriesModule.class.getDeclaredField("paths");
        paths.setAccessible(true);
        paths.set(module, java.util.Collections.singletonList(path));
        final float[][] line = new float[1][];
        OverlayDraw draw = (OverlayDraw) Proxy.newProxyInstance(OverlayDraw.class.getClassLoader(),
                new Class<?>[]{OverlayDraw.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("line")) {
                        line[0] = new float[]{((Number) arguments[0]).floatValue(),
                                ((Number) arguments[2]).floatValue()};
                    }
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == float.class) return 0f;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        WorldProjection projection = new WorldProjection();
        projection.update(29999999.0, 0, 0, 0, 0, 70, 800, 600);
        module.drawWorldOverlay(draw, projection);
        assertNotNull(line[0]);
        assertTrue(Math.abs(line[0][1] - line[0][0]) > 1.0,
                "世界边界附近不同坐标的轨迹端点不能重合");
    }

    @Test
    void recycledTrajectoryDoesNotRenderPreviousImpact() throws Exception {
        TrajectoriesModule module = new TrajectoriesModule();
        Class<?> bufferType = Class.forName(TrajectoriesModule.class.getName() + "$PathBuffer");
        Field bufferField = TrajectoriesModule.class.getDeclaredField("pathBufferA");
        bufferField.setAccessible(true);
        Object buffer = bufferField.get(module);
        Method acquire = bufferType.getDeclaredMethod("acquire");
        Method reset = bufferType.getDeclaredMethod("reset");
        acquire.setAccessible(true);
        reset.setAccessible(true);
        Object path = acquire.invoke(buffer);
        Class<?> pathType = path.getClass();
        Field impact = pathType.getDeclaredField("blockImpact");
        impact.setAccessible(true);
        impact.setBoolean(path, true);
        reset.invoke(buffer);
        Object recycled = acquire.invoke(buffer);
        Method append = TrajectoriesModule.class.getDeclaredMethod("append",
                pathType, double.class, double.class, double.class);
        append.setAccessible(true);
        append.invoke(null, recycled, 0.0, 0.0, 4.0);
        append.invoke(null, recycled, 0.5, 0.0, 4.0);
        Field paths = TrajectoriesModule.class.getDeclaredField("paths");
        paths.setAccessible(true);
        paths.set(module, buffer);
        final int[] quads = new int[1];
        final int[] lines = new int[1];
        OverlayDraw draw = (OverlayDraw) Proxy.newProxyInstance(OverlayDraw.class.getClassLoader(),
                new Class<?>[]{OverlayDraw.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("quad")) quads[0]++;
                    if (method.getName().equals("line")) lines[0]++;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == float.class) return 0f;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        WorldProjection projection = new WorldProjection();
        projection.update(0, 0, 0, 0, 0, 70, 800, 600);
        module.drawWorldOverlay(draw, projection);
        assertEquals(1, lines[0], "新轨迹应绘制");
        assertEquals(0, quads[0], "旧轨迹的命中标记不应保留");
    }

    @Test
    void laterSimulationCannotMoveEarlierEntityImpact() throws Exception {
        TrajectoriesModule module = new TrajectoriesModule();
        Class<?> pathType = Class.forName(TrajectoriesModule.class.getName() + "$PathData");
        Class<?> targetType = Class.forName(TrajectoriesModule.class.getName() + "$EntityTarget");
        Constructor<?> pathConstructor = pathType.getDeclaredConstructor();
        Constructor<?> targetConstructor = targetType.getDeclaredConstructor();
        pathConstructor.setAccessible(true);
        targetConstructor.setAccessible(true);
        Object firstPath = pathConstructor.newInstance();
        Object secondPath = pathConstructor.newInstance();
        Object target = targetConstructor.newInstance();
        Field minX = targetType.getDeclaredField("minX");
        Field maxX = targetType.getDeclaredField("maxX");
        Field minY = targetType.getDeclaredField("minY");
        Field maxY = targetType.getDeclaredField("maxY");
        Field minZ = targetType.getDeclaredField("minZ");
        Field maxZ = targetType.getDeclaredField("maxZ");
        for (Field field : new Field[]{minX, maxX, minY, maxY, minZ, maxZ}) {
            field.setAccessible(true);
        }
        minX.setDouble(target, -1); maxX.setDouble(target, 1);
        minY.setDouble(target, -1); maxY.setDouble(target, 1);
        minZ.setDouble(target, 1); maxZ.setDouble(target, 2);
        ProjectileSimulator.WorldView world = new ProjectileSimulator.WorldView() {
            public boolean solid(double x, double y, double z) { return false; }
            public boolean water(double x, double y, double z) { return false; }
            public boolean loaded(double x, double y, double z) { return true; }
            public double minY() { return -64; }
        };
        Method simulate = TrajectoriesModule.class.getDeclaredMethod("simulate",
                ProjectileSimulator.class, ProjectileSimulator.WorldView.class,
                int.class, java.util.List.class, pathType);
        simulate.setAccessible(true);
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureShot(ProjectileSimulator.WIND_CHARGE,
                0, 0, 0, 0, 0, 0, 0, 1, 0, false));
        simulate.invoke(module, simulator, world, 0, java.util.Collections.singletonList(target), firstPath);
        minX.setDouble(target, -2);
        assertTrue(simulator.configureShot(ProjectileSimulator.WIND_CHARGE,
                0, 0, 0, 0, 0, 0, 0, 1, 0, false));
        simulate.invoke(module, simulator, world, 0, java.util.Collections.singletonList(target), secondPath);
        Field hit = pathType.getDeclaredField("entity");
        hit.setAccessible(true);
        assertNotNull(hit.get(firstPath));
        assertNotNull(hit.get(secondPath));
        assertEquals(-1.0, minX.getDouble(hit.get(firstPath)), 1.0e-6);
        assertEquals(-2.0, minX.getDouble(hit.get(secondPath)), 1.0e-6);
    }

    @Test
    void unloadedOriginDoesNotDrawPhantomLine() throws Exception {
        TrajectoriesModule module = new TrajectoriesModule();
        Class<?> pathType = Class.forName(TrajectoriesModule.class.getName() + "$PathData");
        Constructor<?> constructor = pathType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object path = constructor.newInstance();
        ProjectileSimulator.WorldView world = new ProjectileSimulator.WorldView() {
            public boolean loaded(double x, double y, double z) { return false; }
            public boolean solid(double x, double y, double z) { throw new AssertionError("unloaded"); }
            public boolean water(double x, double y, double z) { throw new AssertionError("unloaded"); }
            public double minY() { return -64; }
        };
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureShot(ProjectileSimulator.WIND_CHARGE,
                0, 0, 4, 0, 0, 0, 0, 1, 0, false));
        Method simulate = TrajectoriesModule.class.getDeclaredMethod("simulate",
                ProjectileSimulator.class, ProjectileSimulator.WorldView.class,
                int.class, java.util.List.class, pathType);
        simulate.setAccessible(true);
        simulate.invoke(module, simulator, world, 0, java.util.Collections.emptyList(), path);
        Field paths = TrajectoriesModule.class.getDeclaredField("paths");
        paths.setAccessible(true);
        paths.set(module, java.util.Collections.singletonList(path));
        final int[] lines = new int[1];
        OverlayDraw draw = (OverlayDraw) Proxy.newProxyInstance(OverlayDraw.class.getClassLoader(),
                new Class<?>[]{OverlayDraw.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("line")) lines[0]++;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == float.class) return 0f;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });
        WorldProjection projection = new WorldProjection();
        projection.update(0, 0, 0, 0, 0, 70, 800, 600);
        module.drawWorldOverlay(draw, projection);
        assertEquals(0, lines[0]);
    }
}
