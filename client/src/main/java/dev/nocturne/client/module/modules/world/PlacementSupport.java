package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;

/** 在目标位置附近寻找真实可点击支撑面。 */
public final class PlacementSupport {
    /** 侧面顺序与面名一一对应：+X=west、-X=east、+Z=north、-Z=south。 */
    private static final int[][] SIDES = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}
    };
    /** 下标 0..3 与 {@link #SIDES} 对应；下标 4 是"放在方块顶面"。 */
    private static final String[] FACES = {"west", "east", "north", "south", "up"};
    private static final int UP_FACE = 4;
    /** 支撑面最多向下找 4 格：4.5 的交互距离不可能点到更远的方块。 */
    private static final int MAX_DEPTH = 4;
    /** 原版交互距离（从眼睛算起）。 */
    private static final double MAX_REACH = 4.5;
    private static final double EYE_HEIGHT = 1.62;

    private PlacementSupport() {
    }

    /**
     * 优先目标下方顶面（y-1 起），其次目标位置四个侧面；全部失败才返回 false。
     *
     * <p>目标格必须可替换：目标位置已经有方块时，点它只会"成功"却什么都没放。
     * 支撑面必须在交互距离内，且未加载区块（状态读不到）一律不算支撑；某个支撑面超距或
     * 放置被拒绝时继续找下一个，不能因为第一个候选不可用就整次放弃。
     */
    public static boolean placeNear(GameBridge bridge, ActionApi actions, int x, int y, int z) {
        if (bridge == null || actions == null) {
            return false;
        }
        Object target = bridge.blockStateAt(x, y, z);
        if (target == null || !bridge.canBeReplaced(target)) {
            return false;
        }
        Object player = bridge.player();
        double[] position = player == null ? null : bridge.entityPosition(player);
        if (position == null) {
            return false;
        }
        for (int[] candidate : supportCandidates(x, y, z)) {
            if (!reachable(position, candidate[0], candidate[1], candidate[2])) {
                continue;
            }
            Object support = bridge.blockStateAt(candidate[0], candidate[1], candidate[2]);
            if (support == null || bridge.isAir(support)) {
                continue;
            }
            if (actions.placeAt(candidate[0], candidate[1], candidate[2], FACES[candidate[3]])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 支撑面候选顺序：{@code y-1..y-4} 的顶面，再四个侧面。
     *
     * @return 每个元素为 {@code {x, y, z, 面下标}}，面下标指向 {@link #FACES}
     */
    static int[][] supportCandidates(int x, int y, int z) {
        int[][] candidates = new int[MAX_DEPTH + SIDES.length][];
        int cursor = 0;
        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            candidates[cursor++] = new int[]{x, y - depth, z, UP_FACE};
        }
        for (int i = 0; i < SIDES.length; i++) {
            candidates[cursor++] = new int[]{x + SIDES[i][0], y, z + SIDES[i][2], i};
        }
        return candidates;
    }

    /** 从玩家眼睛到方块中心是否在交互距离内。 */
    static boolean reachable(double[] position, int x, int y, int z) {
        if (position == null || position.length < 3) {
            return false;
        }
        double dx = x + 0.5 - position[0];
        double dy = y + 0.5 - (position[1] + EYE_HEIGHT);
        double dz = z + 0.5 - position[2];
        return dx * dx + dy * dy + dz * dz <= MAX_REACH * MAX_REACH;
    }
}
