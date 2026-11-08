package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;

/** 在目标位置附近寻找真实可点击支撑面。 */
public final class PlacementSupport {
    /** 侧面顺序与面名一一对应：+X=west、-X=east、+Z=north、-Z=south。 */
    private static final int[][] SIDES = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final String[] FACES = {"west", "east", "north", "south"};
    /** 支撑面最多向下找 4 格：4.5 的交互距离不可能点到更远的方块。 */
    private static final int MAX_DEPTH = 4;
    /** 原版交互距离（从眼睛算起）。 */
    private static final double MAX_REACH = 4.5;
    private static final double EYE_HEIGHT = 1.62;

    private PlacementSupport() {
    }

    /**
     * 优先目标下方顶面（y-1 起），其次目标位置四个侧面；全部无支撑时返回 false。
     *
     * <p>目标格必须可替换：目标位置已经有方块时，点它只会"成功"却什么都没放。
     * 支撑面必须在交互距离内，且未加载区块（状态读不到）一律不算支撑。
     */
    public static boolean placeNear(GameBridge bridge, ActionApi actions, int x, int y, int z) {
        if (bridge == null || actions == null) {
            return false;
        }
        Object target = bridge.blockStateAt(x, y, z);
        if (target == null || !bridge.canBeReplaced(target)) {
            return false;
        }
        for (int depth = 1; depth <= MAX_DEPTH; depth++) {
            int supportY = y - depth;
            Object support = bridge.blockStateAt(x, supportY, z);
            if (support == null || bridge.isAir(support)) {
                continue;
            }
            return withinReach(bridge, x, supportY, z) && actions.placeAt(x, supportY, z, "up");
        }
        for (int i = 0; i < SIDES.length; i++) {
            int[] side = SIDES[i];
            int sx = x + side[0];
            int sz = z + side[2];
            Object support = bridge.blockStateAt(sx, y, sz);
            if (support == null || bridge.isAir(support)) {
                continue;
            }
            return withinReach(bridge, sx, y, sz) && actions.placeAt(sx, y, sz, FACES[i]);
        }
        return false;
    }

    /** 从玩家眼睛到方块中心是否在交互距离内；玩家位置读不到时按超距处理。 */
    private static boolean withinReach(GameBridge bridge, int x, int y, int z) {
        Object player = bridge.player();
        double[] position = player == null ? null : bridge.entityPosition(player);
        if (position == null) {
            return false;
        }
        double dx = x + 0.5 - position[0];
        double dy = y + 0.5 - (position[1] + EYE_HEIGHT);
        double dz = z + 0.5 - position[2];
        return dx * dx + dy * dy + dz * dz <= MAX_REACH * MAX_REACH;
    }
}
