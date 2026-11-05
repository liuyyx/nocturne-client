package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;

/** 在目标位置附近寻找真实可点击支撑面。 */
public final class PlacementSupport {
    private static final int[][] SIDES = {
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final String[] FACES = {"west", "east", "north", "south"};

    private PlacementSupport() {
    }

    /** 优先目标下方顶面，其次目标位置四个侧面；全部无支撑时返回 false。 */
    public static boolean placeNear(GameBridge bridge, ActionApi actions, int x, int y, int z) {
        for (int depth = 0; depth <= 8; depth++) {
            int supportY = y - depth;
            if (!bridge.isAir(bridge.blockStateAt(x, supportY, z))) {
                return actions.placeAt(x, supportY, z, "up");
            }
        }
        for (int i = 0; i < SIDES.length; i++) {
            int[] side = SIDES[i];
            int sx = x + side[0];
            int sz = z + side[2];
            if (!bridge.isAir(bridge.blockStateAt(sx, y, sz))) {
                return actions.placeAt(sx, y, sz, FACES[i]);
            }
        }
        return false;
    }
}
