package dev.nocturne.client.module.modules.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoleScanTest {

    @Test
    void classifiesSafeSingleAtNegativeBoundaryCoordinates() {
        HoleScan.Grid grid = solidGrid();
        int x = -2;
        int y = 1;
        int z = -3;
        carve(grid, x, y, z);
        bedrock(grid, x, y - 1, z);
        bedrock(grid, x - 1, y, z);
        bedrock(grid, x + 1, y, z);
        bedrock(grid, x, y, z - 1);
        bedrock(grid, x, y, z + 1);

        List<int[]> safe = ofType(scan(grid), HoleScan.SAFE);

        assertEquals(1, safe.size());
        assertArrayEquals(new int[]{-2, 1, -3, -1, -2, HoleScan.SAFE}, safe.get(0));
        assertTrue(ofType(scan(grid), HoleScan.UNSAFE).isEmpty());
    }

    @Test
    void classifiesDoubleXFromItsMinimumCornerOnlyOnce() {
        HoleScan.Grid grid = solidGrid();
        carve(grid, 0, 0, 0);
        carve(grid, 1, 0, 0);

        List<int[]> holes = holes(scan(grid));

        assertEquals(1, holes.size(), "双格洞不得退化成两个单格或重复盒");
        assertArrayEquals(new int[]{0, 0, 0, 2, 1, HoleScan.UNSAFE}, holes.get(0));
    }

    @Test
    void classifiesDoubleZFromItsMinimumCornerOnlyOnce() {
        HoleScan.Grid grid = solidGrid();
        carve(grid, 0, 0, 0);
        carve(grid, 0, 0, 1);

        List<int[]> holes = holes(scan(grid));

        assertEquals(1, holes.size());
        assertArrayEquals(new int[]{0, 0, 0, 1, 2, HoleScan.UNSAFE}, holes.get(0));
    }

    @Test
    void quadTakesPriorityOverOverlappingDoubleHoles() {
        HoleScan.Grid grid = solidGrid();
        carve(grid, 0, 0, 0);
        carve(grid, 1, 0, 0);
        carve(grid, 0, 0, 1);
        carve(grid, 1, 0, 1);

        List<int[]> holes = holes(scan(grid));

        assertEquals(1, holes.size(), "2x2 不得同时产生双格盒或四个单格盒");
        assertArrayEquals(new int[]{0, 0, 0, 2, 2, HoleScan.UNSAFE}, holes.get(0));
    }

    @Test
    void safetyRequiresEveryFloorAndExternalSideToBeBedrock() {
        HoleScan.Grid grid = solidGrid();
        carve(grid, 0, 0, 0);
        bedrock(grid, 0, -1, 0);
        bedrock(grid, -1, 0, 0);
        bedrock(grid, 1, 0, 0);
        bedrock(grid, 0, 0, -1);
        bedrock(grid, 0, 0, 1);
        assertEquals(1, ofType(scan(grid), HoleScan.SAFE).size());

        solid(grid, 1, 0, 0);
        assertEquals(1, ofType(scan(grid), HoleScan.UNSAFE).size(),
                "任意一侧不是基岩都必须降级为不安全洞");
        assertTrue(ofType(scan(grid), HoleScan.SAFE).isEmpty());

        bedrock(grid, 1, 0, 0);
        solid(grid, 0, -1, 0);
        assertEquals(1, ofType(scan(grid), HoleScan.UNSAFE).size(),
                "脚下不是基岩时即使四侧都是基岩也不安全");
        assertTrue(ofType(scan(grid), HoleScan.SAFE).isEmpty());
    }

    @Test
    void doubleHoleSafetyChecksTheWholeOuterRing() {
        HoleScan.Grid grid = solidGrid();
        carve(grid, 0, 0, 0);
        carve(grid, 1, 0, 0);
        bedrock(grid, 0, -1, 0);
        bedrock(grid, 1, -1, 0);
        bedrock(grid, -1, 0, 0);
        bedrock(grid, 2, 0, 0);
        bedrock(grid, 0, 0, -1);
        bedrock(grid, 1, 0, -1);
        bedrock(grid, 0, 0, 1);
        bedrock(grid, 1, 0, 1);
        assertArrayEquals(new int[]{0, 0, 0, 2, 1, HoleScan.SAFE},
                ofType(scan(grid), HoleScan.SAFE).get(0));

        solid(grid, 1, 0, -1);
        assertArrayEquals(new int[]{0, 0, 0, 2, 1, HoleScan.UNSAFE},
                ofType(scan(grid), HoleScan.UNSAFE).get(0));
    }

    @Test
    void holeCellRequiresCollisionAndTwoAirBlocksAbove() {
        HoleScan.Grid grid = solidGrid();
        carve(grid, 0, 0, 0);
        assertTrue(HoleScan.isHoleCell(grid, 0, 0, 0));

        grid.set(0, -1, 0, false, false, false);
        assertFalse(HoleScan.isHoleCell(grid, 0, 0, 0), "非空气但无碰撞的地板不能形成洞");

        bedrock(grid, 0, -1, 0);
        solid(grid, 0, 2, 0);
        assertFalse(HoleScan.isHoleCell(grid, 0, 0, 0), "上方第二格不是空气时不能形成洞");
    }

    @Test
    void openOuterSideRejectsHoleInsteadOfDrawingAnOpenBox() {
        HoleScan.Grid grid = solidGrid();
        carve(grid, 0, 0, 0);
        grid.set(1, 0, 0, true, false, false);

        assertTrue(holes(scan(grid)).isEmpty());
    }

    @Test
    void wallUsesThreeConsecutiveBedrockBlocksAndFirstStandableAirSide() {
        HoleScan.Grid grid = solidGrid();
        bedrock(grid, 0, -1, 0);
        bedrock(grid, 0, 0, 0);
        bedrock(grid, 0, 1, 0);
        air(grid, 0, 0, -1);
        air(grid, 0, 1, -1);

        List<int[]> result = scan(grid);
        List<int[]> walls = ofType(result, HoleScan.WALL);
        List<int[]> sides = ofType(result, HoleScan.WALL_SIDE);
        assertEquals(1, walls.size());
        assertArrayEquals(new int[]{0, 0, 0, 1, 1, HoleScan.WALL}, walls.get(0));
        assertEquals(1, sides.size());
        assertArrayEquals(new int[]{0, 0, -1, 1, 0, HoleScan.WALL_SIDE}, sides.get(0),
                "固定方向顺序应先选择北侧可站立空气格");

        solid(grid, 0, 1, 0);
        assertTrue(ofType(scan(grid), HoleScan.WALL).isEmpty(),
                "缺少 y+1 基岩就不是连续三连基岩墙");
        assertTrue(ofType(scan(grid), HoleScan.WALL_SIDE).isEmpty());
    }

    @Test
    void wallWithoutAirSideDoesNotEmitWallSideBox() {
        HoleScan.Grid grid = solidGrid();
        bedrock(grid, 0, -1, 0);
        bedrock(grid, 0, 0, 0);
        bedrock(grid, 0, 1, 0);

        List<int[]> result = scan(grid);

        assertEquals(1, ofType(result, HoleScan.WALL).size());
        assertTrue(ofType(result, HoleScan.WALL_SIDE).isEmpty());
    }

    @Test
    void fadeIsOpaqueThroughStartThenLinearToZeroAtDistance() {
        assertEquals(1.0, HoleScan.distanceAlpha(4.0, 5.0, 6.0));
        assertEquals(1.0, HoleScan.distanceAlpha(5.0, 5.0, 6.0));
        assertEquals(0.5, HoleScan.distanceAlpha(5.5, 5.0, 6.0), 1.0e-9);
        assertEquals(0.0, HoleScan.distanceAlpha(6.0, 5.0, 6.0));
        assertEquals(0.0, HoleScan.distanceAlpha(7.0, 5.0, 6.0));
        assertEquals(1.0, HoleScan.distanceAlpha(6.0, 8.0, 6.0),
                "Start Fade 超过 Distance 时整个可见范围保持不透明");
    }

    private static HoleScan.Grid solidGrid() {
        HoleScan.Grid grid = new HoleScan.Grid(-4, -3, -4, 5, 5, 5);
        for (int y = -3; y <= 5; y++) {
            for (int x = -4; x <= 5; x++) {
                for (int z = -4; z <= 5; z++) {
                    solid(grid, x, y, z);
                }
            }
        }
        return grid;
    }

    private static List<int[]> scan(HoleScan.Grid grid) {
        return HoleScan.scan(grid, -3, 3, -2, 3, -3, 3);
    }

    private static List<int[]> holes(List<int[]> snapshots) {
        List<int[]> result = new ArrayList<int[]>();
        result.addAll(ofType(snapshots, HoleScan.SAFE));
        result.addAll(ofType(snapshots, HoleScan.UNSAFE));
        return result;
    }

    private static List<int[]> ofType(List<int[]> snapshots, int type) {
        List<int[]> result = new ArrayList<int[]>();
        for (int[] snapshot : snapshots) {
            if (snapshot[5] == type) {
                result.add(snapshot);
            }
        }
        return result;
    }

    private static void carve(HoleScan.Grid grid, int x, int y, int z) {
        air(grid, x, y, z);
        air(grid, x, y + 1, z);
        air(grid, x, y + 2, z);
    }

    private static void air(HoleScan.Grid grid, int x, int y, int z) {
        grid.set(x, y, z, true, false, false);
    }

    private static void solid(HoleScan.Grid grid, int x, int y, int z) {
        grid.set(x, y, z, false, true, false);
    }

    private static void bedrock(HoleScan.Grid grid, int x, int y, int z) {
        grid.set(x, y, z, false, true, true);
    }
}
