package dev.nocturne.client.module.modules.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 支撑面候选与交互距离。
 *
 * <p>回归：第一个支撑面超距时旧实现直接 {@code return false}，不会再看侧面/更浅的支撑，
 * 站在方块边缘常常"有支撑却不放"。
 */
class PlacementSupportTest {

    @Test
    void candidatesTryShallowerSupportsBeforeSides() {
        int[][] candidates = PlacementSupport.supportCandidates(10, 64, -3);
        assertEquals(8, candidates.length);
        // 前四个是 y-1..y-4 的顶面
        for (int depth = 1; depth <= 4; depth++) {
            int[] candidate = candidates[depth - 1];
            assertEquals(10, candidate[0]);
            assertEquals(64 - depth, candidate[1]);
            assertEquals(-3, candidate[2]);
        }
        // 之后是四个侧面：+X、-X、+Z、-Z
        assertEquals(11, candidates[4][0]);
        assertEquals(9, candidates[5][0]);
        assertEquals(-2, candidates[6][2]);
        assertEquals(-4, candidates[7][2]);
    }

    @Test
    void reachUsesEyeHeight() {
        double[] player = {0.5, 64.0, 0.5};
        // 正下方一格：眼睛在 65.62，方块中心 63.5，距离约 2.12 —— 在 4.5 内
        assertTrue(PlacementSupport.reachable(player, 0, 63, 0));
        // 水平 5 格：必超
        assertFalse(PlacementSupport.reachable(player, 5, 64, 0));
        // 玩家位置读不到（null）时按超距处理
        assertFalse(PlacementSupport.reachable(null, 0, 63, 0));
    }

    /** 目标正下方的支撑面超距，但侧面支撑仍在距离内：循环必须继续，而不是第一个就放弃。 */
    @Test
    void overReachingSupportDoesNotAbortTheSearch() {
        int x = 0;
        int y = 64;
        int z = 0;
        double[] player = {4.6, 64.0, 0.5};
        int[][] candidates = PlacementSupport.supportCandidates(x, y, z);
        assertFalse(PlacementSupport.reachable(player, candidates[0][0], candidates[0][1],
                candidates[0][2]), "the first (straight below) support is out of reach");

        boolean anyReachable = false;
        for (int[] candidate : candidates) {
            if (PlacementSupport.reachable(player, candidate[0], candidate[1], candidate[2])) {
                anyReachable = true;
            }
        }
        assertTrue(anyReachable, "a side support inside reach must remain usable");
    }
}
