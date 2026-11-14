package dev.nocturne.client.module.modules.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProjectileSimulator} 的单元测试：运动表推进次序、体素 DDA 碰撞与命中面、
 * 弓蓄力公式、钓鱼浮漂配置与出界停止。世界用假网格，不触碰游戏类。
 */
class ProjectileSimulatorTest {

    /** 假世界：稀疏实心格集合 + 全局水开关。 */
    private static final class Grid implements ProjectileSimulator.WorldView {
        private final java.util.Set<Long> solid = new java.util.HashSet<Long>();
        private boolean water;
        private double minY = -64.0;

        static long key(int x, int y, int z) {
            return (((long) x & 0x1FFFFF) << 42) | (((long) z & 0x1FFFFF) << 21) | ((long) y & 0x1FFFFF);
        }

        void solid(int x, int y, int z) {
            solid.add(key(x, y, z));
        }

        @Override
        public boolean solid(double x, double y, double z) {
            return solid.contains(key((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        }

        @Override
        public boolean water(double x, double y, double z) {
            return water;
        }

        @Override
        public boolean loaded(double x, double y, double z) {
            return true;
        }

        @Override
        public double minY() {
            return minY;
        }
    }

    @Test
    void bowPowerFollowsVanillaCurve() {
        assertEquals(0.0f, ProjectileSimulator.bowPower(0), 1.0E-6);
        assertEquals(0.140625f, ProjectileSimulator.bowPower(5), 1.0E-6);
        assertEquals(0.75f, ProjectileSimulator.bowPower(20), 1.0E-6);
        assertEquals(1.0f, ProjectileSimulator.bowPower(40), 1.0E-6);
    }

    @Test
    void straightShotTravelsAlongViewDirection() {
        Grid grid = new Grid();
        ProjectileSimulator simulator = new ProjectileSimulator();
        // 风弹：重力 0、阻力 1，路径必须严格直线；yaw=0/pitch=0 朝 +Z。
        assertTrue(simulator.configureShot(ProjectileSimulator.WIND_CHARGE,
                0.5, 10.0, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, false));
        for (int i = 0; i < 10; i++) {
            ProjectileSimulator.Step step = simulator.tick(grid);
            assertFalse(step.stop);
        }
        assertEquals(0.5, simulator.x(), 1.0E-6);
        assertEquals(10.0, simulator.y(), 1.0E-6);
        assertEquals(0.5 + 10 * 1.5, simulator.z(), 1.0E-6);
    }

    @Test
    void snowballAppliesGravityThenDrag() {
        Grid grid = new Grid();
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureShot(ProjectileSimulator.SNOWBALL,
                0.5, 10.0, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, false));
        ProjectileSimulator.Step step = simulator.tick(grid);
        assertFalse(step.stop);
        // 第一 tick：先重力 vy=-0.03，再空气阻力 ×0.99，位移即该速度（速度本身是每 tick 位移）。
        double expectedY = 10.0 + (-0.03) * 0.99;
        assertEquals(expectedY, simulator.y(), 1.0E-9);
    }

    @Test
    void arrowUsesPositionDragGravityOrder() {
        Grid grid = new Grid();
        ProjectileSimulator arrow = new ProjectileSimulator();
        ProjectileSimulator snowball = new ProjectileSimulator();
        assertTrue(arrow.configureShot(ProjectileSimulator.ARROW.withPower(1.5),
                0.5, 10.0, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, false));
        assertTrue(snowball.configureShot(ProjectileSimulator.SNOWBALL,
                0.5, 10.0, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, false));
        // 同初速同重力同阻力，但箭先位移后结算 → 第一 tick 后箭更高（位移未被当 tick 扣重力）。
        arrow.tick(grid);
        snowball.tick(grid);
        assertTrue(arrow.y() > snowball.y() + 0.02);
    }

    @Test
    void ddaStopsAtSolidCellAndReportsFace() {
        Grid grid = new Grid();
        grid.solid(0, 1, 3);
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureShot(ProjectileSimulator.WIND_CHARGE,
                0.5, 1.5, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, false));
        ProjectileSimulator.Step step = null;
        for (int i = 0; i < 10 && (step == null || !step.stop); i++) {
            step = simulator.tick(grid);
        }
        assertTrue(step.stop);
        assertEquals(2, step.faceAxis);
        assertEquals(-1, step.faceSign);
        // 位置钳在 z=3 格边界。
        assertEquals(3.0, step.z, 1.0E-6);
    }

    @Test
    void ddaReportsVerticalFace() {
        Grid grid = new Grid();
        grid.solid(0, 0, 0);
        ProjectileSimulator simulator = new ProjectileSimulator();
        // 垂直向下：yaw 任意、pitch=90。
        assertTrue(simulator.configureShot(ProjectileSimulator.WIND_CHARGE,
                0.5, 5.0, 0.5, 0.0, 90.0, 0.0, 0.0, 1.0, 0.0, false));
        ProjectileSimulator.Step step = null;
        for (int i = 0; i < 20 && (step == null || !step.stop); i++) {
            step = simulator.tick(grid);
        }
        assertTrue(step.stop);
        assertEquals(1, step.faceAxis);
        assertEquals(1, step.faceSign);
        assertEquals(1.0, step.y, 1.0E-6);
    }

    @Test
    void fallingBelowMinYStops() {
        Grid grid = new Grid();
        grid.minY = 0.0;
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureShot(ProjectileSimulator.SNOWBALL,
                0.5, 1.2, 0.5, 0.0, 90.0, 0.0, 0.0, 1.0, 0.0, false));
        boolean stopped = false;
        for (int i = 0; i < 100 && !stopped; i++) {
            stopped = simulator.tick(grid).stop;
        }
        assertTrue(stopped);
    }

    @Test
    void chargedBowUsesProvidedPowerWithoutChangingArrowParameters() {
        Grid grid = new Grid();
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureShot(ProjectileSimulator.ARROW, 3.0,
                0.5, 10.0, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, false));
        assertFalse(simulator.tick(grid).stop);
        assertEquals(3.5, simulator.z(), 1.0E-9);
        assertEquals(10.0, simulator.y(), 1.0E-9);
    }

    @Test
    void reconfigurationDoesNotLeakPreviousProjectileGravity() {
        Grid grid = new Grid();
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureFired(ProjectileSimulator.SNOWBALL,
                0.5, 10.0, 0.5, 0.0, 0.0, 1.0, false, 0, true));
        simulator.tick(grid);
        assertTrue(simulator.configureShot(ProjectileSimulator.SNOWBALL,
                0.5, 10.0, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, false));
        simulator.tick(grid);
        assertEquals(10.0 - 0.03 * 0.99, simulator.y(), 1.0E-9);
    }

    @Test
    void unmappedWorldMinimumDoesNotStopNegativeHeightTrajectory() throws Exception {
        java.lang.reflect.Constructor<?> constructor = Class.forName(
                "dev.nocturne.client.module.modules.render.TrajectoriesModule$BridgeWorldView")
                .getDeclaredConstructor(dev.nocturne.client.game.GameBridge.class, Object.class);
        constructor.setAccessible(true);
        ProjectileSimulator.WorldView world = (ProjectileSimulator.WorldView) constructor.newInstance(
                new dev.nocturne.client.game.GameBridge(null,
                        new dev.nocturne.client.mapping.IdentityMapping()), new Object());
        Grid grid = new Grid();
        grid.minY = world.minY();
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureShot(ProjectileSimulator.ARROW.withPower(3.0),
                0.5, -58.5, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, false));
        assertFalse(simulator.tick(grid).stop);
        assertFalse(simulator.tick(grid).stop);
    }

    @Test
    void unloadedDestinationStopsBeforeReadingBlocks() {
        ProjectileSimulator.WorldView world = new ProjectileSimulator.WorldView() {
            public boolean loaded(double x, double y, double z) { return z < 1.0; }
            public boolean solid(double x, double y, double z) {
                assertTrue(z < 1.0, "未加载区块不能读取方块");
                return false;
            }
            public boolean water(double x, double y, double z) {
                assertTrue(z < 1.0, "未加载区块不能读取水状态");
                return false;
            }
            public double minY() { return -64; }
        };
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureShot(ProjectileSimulator.WIND_CHARGE,
                0.5, 1.5, 0.5, 0, 0, 0, 0, 1, 0, false));
        assertTrue(simulator.tick(world).stop);
    }

    @Test
    void fastProjectileStopsAtUnloadedIntermediateChunk() {
        ProjectileSimulator.WorldView world = new ProjectileSimulator.WorldView() {
            public boolean loaded(double x, double y, double z) { return z < 16 || z >= 32; }
            public boolean solid(double x, double y, double z) {
                assertTrue(loaded(x, y, z), "不能查询未加载区块");
                return false;
            }
            public boolean water(double x, double y, double z) { return false; }
            public double minY() { return -64; }
        };
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureFired(ProjectileSimulator.WIND_CHARGE,
                0.5, 10, 0.5, 0, 0, 40, false, 0, false));
        ProjectileSimulator.Step step = simulator.tick(world);
        assertTrue(step.stop);
        assertEquals(-1, step.faceAxis);
        assertEquals(16.0, step.z, 1.0E-9);
    }

    @Test
    void oversizedStepStopsAtTraversalLimitInsteadOfSkippingCells() {
        Grid grid = new Grid();
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureFired(ProjectileSimulator.WIND_CHARGE,
                0.5, 10, 0.5, 0, 0, 1000, false, 0, false));
        ProjectileSimulator.Step step = simulator.tick(grid);
        assertTrue(step.stop);
        assertEquals(-1, step.faceAxis);
        assertEquals(512.0, step.z, 1.0E-9);
    }

    @Test
    void firedConfigurationRejectsZeroVelocity() {
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertFalse(simulator.configureFired(ProjectileSimulator.ARROW,
                0, 0, 0, 0, 0, 0, false, 0, false));
    }

    @Test
    void fishingBobberFliesAndSinks() {
        Grid grid = new Grid();
        ProjectileSimulator simulator = new ProjectileSimulator();
        simulator.configureFishing(0.5, 5.0, 0.5, 30.0, -20.0);
        double previousY = simulator.y();
        boolean descended = false;
        for (int i = 0; i < 60; i++) {
            simulator.tick(grid);
            if (simulator.y() < previousY) {
                descended = true;
                break;
            }
            previousY = simulator.y();
        }
        assertTrue(descended);
    }

    @Test
    void noGravityOverrideKeepsHeight() {
        Grid grid = new Grid();
        ProjectileSimulator simulator = new ProjectileSimulator();
        assertTrue(simulator.configureShot(ProjectileSimulator.ARROW.withPower(1.5),
                0.5, 10.0, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, true));
        for (int i = 0; i < 20; i++) {
            simulator.tick(grid);
        }
        assertEquals(10.0, simulator.y(), 1.0E-6);
    }
}
