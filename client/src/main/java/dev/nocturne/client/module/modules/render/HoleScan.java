package dev.nocturne.client.module.modules.render;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hole ESP 的纯 Java 扫描核心。输入是布尔方块网格，输出均为可直接绘制的快照；
 * 坐标中的 x1/z1 是排他的，y 则是洞、空气标记或墙的底面。
 */
final class HoleScan {
    static final int AIR = 0;
    static final int UNSAFE = 1;
    static final int SAFE = 2;
    static final int WALL = 3;
    static final int WALL_SIDE = 4;

    private static final int[][] HORIZONTAL = {
            {0, -1}, {0, 1}, {-1, 0}, {1, 0}
    };

    private HoleScan() {
    }

    /** 三维布尔网格；越界读取一律按非空气、无碰撞、非基岩处理。 */
    static final class Grid {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final boolean[] air;
        private final boolean[] collision;
        private final boolean[] bedrock;

        Grid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            if (maxX < minX || maxY < minY || maxZ < minZ) {
                throw new IllegalArgumentException("empty grid");
            }
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = maxX - minX + 1;
            this.sizeY = maxY - minY + 1;
            this.sizeZ = maxZ - minZ + 1;
            long volume = (long) sizeX * sizeY * sizeZ;
            if (volume > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("grid too large");
            }
            int length = (int) volume;
            air = new boolean[length];
            collision = new boolean[length];
            bedrock = new boolean[length];
        }

        void set(int x, int y, int z, boolean isAir,
                 boolean hasCollision, boolean isBedrock) {
            int index = index(x, y, z);
            if (index < 0) {
                throw new IndexOutOfBoundsException("cell outside grid");
            }
            air[index] = isAir;
            collision[index] = hasCollision;
            bedrock[index] = isBedrock;
        }

        boolean air(int x, int y, int z) {
            int index = index(x, y, z);
            return index >= 0 && air[index];
        }

        boolean collision(int x, int y, int z) {
            int index = index(x, y, z);
            return index >= 0 && collision[index];
        }

        boolean bedrock(int x, int y, int z) {
            int index = index(x, y, z);
            return index >= 0 && bedrock[index];
        }

        private int index(int x, int y, int z) {
            int dx = x - minX;
            int dy = y - minY;
            int dz = z - minZ;
            if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) {
                return -1;
            }
            return (dy * sizeZ + dz) * sizeX + dx;
        }
    }

    /** 扫描指定闭区间；同一组洞格只会产生一个以最小 x/z 角为原点的包围盒。 */
    static List<int[]> scan(Grid grid, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        List<int[]> result = new ArrayList<int[]>();
        Set<Long> consumedHoles = new HashSet<Long>();
        Set<Long> emittedWallSides = new HashSet<Long>();
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isWall(grid, x, y, z)) {
                        result.add(box(x, y, z, x + 1, z + 1, WALL));
                        int side = wallSide(grid, x, y, z);
                        if (side >= 0) {
                            int sideX = x + HORIZONTAL[side][0];
                            int sideZ = z + HORIZONTAL[side][1];
                            if (emittedWallSides.add(key(sideX, y, sideZ))) {
                                result.add(box(sideX, y, sideZ, sideX + 1, sideZ + 1, WALL_SIDE));
                            }
                        }
                    }
                    if (grid.air(x, y, z) && grid.collision(x, y + 1, z)) {
                        result.add(box(x, y, z, x + 1, z + 1, AIR));
                    }
                    if (consumedHoles.contains(key(x, y, z))) {
                        continue;
                    }
                    int[] hole = findHole(grid, x, y, z);
                    if (hole == null) {
                        continue;
                    }
                    result.add(hole);
                    for (int holeX = hole[0]; holeX < hole[3]; holeX++) {
                        for (int holeZ = hole[2]; holeZ < hole[4]; holeZ++) {
                            consumedHoles.add(key(holeX, y, holeZ));
                        }
                    }
                }
            }
        }
        return result;
    }

    /** 洞格下方必须是非空气且有碰撞的方块，本格与上方两格必须是空气。 */
    static boolean isHoleCell(Grid grid, int x, int y, int z) {
        return grid.collision(x, y - 1, z)
                && grid.air(x, y, z)
                && grid.air(x, y + 1, z)
                && grid.air(x, y + 2, z);
    }

    /** 距离不超过 Start Fade 时保持不透明，之后到 Distance 线性归零。 */
    static double distanceAlpha(double blockDistance, double startFade, double distance) {
        if (blockDistance > distance) {
            return 0.0;
        }
        double fadeStart = Math.min(startFade, distance);
        if (blockDistance <= fadeStart || fadeStart >= distance) {
            return 1.0;
        }
        double alpha = (distance - blockDistance) / (distance - fadeStart);
        return alpha < 0.0 ? 0.0 : (alpha > 1.0 ? 1.0 : alpha);
    }

    private static int[] findHole(Grid grid, int x, int y, int z) {
        if (!isHoleCell(grid, x, y, z)) {
            return null;
        }
        int[] hole = createHole(grid, x, y, z, 2, 2);
        if (hole != null) {
            return hole;
        }
        hole = createHole(grid, x, y, z, 2, 1);
        if (hole != null) {
            return hole;
        }
        hole = createHole(grid, x, y, z, 1, 2);
        if (hole != null) {
            return hole;
        }
        return createHole(grid, x, y, z, 1, 1);
    }

    private static int[] createHole(Grid grid, int x, int y, int z, int width, int depth) {
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                if (!isHoleCell(grid, x + dx, y, z + dz)) {
                    return null;
                }
            }
        }
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                for (int[] direction : HORIZONTAL) {
                    int sideX = x + dx + direction[0];
                    int sideZ = z + dz + direction[1];
                    if (!inside(sideX, sideZ, x, z, width, depth) && !grid.collision(sideX, y, sideZ)) {
                        return null;
                    }
                }
            }
        }
        return box(x, y, z, x + width, z + depth,
                isSafeHole(grid, x, y, z, width, depth) ? SAFE : UNSAFE);
    }

    private static boolean isSafeHole(Grid grid, int x, int y, int z, int width, int depth) {
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < depth; dz++) {
                int cellX = x + dx;
                int cellZ = z + dz;
                if (!grid.bedrock(cellX, y - 1, cellZ)) {
                    return false;
                }
                for (int[] direction : HORIZONTAL) {
                    int sideX = cellX + direction[0];
                    int sideZ = cellZ + direction[1];
                    if (!inside(sideX, sideZ, x, z, width, depth)
                            && !grid.bedrock(sideX, y, sideZ)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean inside(int x, int z, int originX, int originZ, int width, int depth) {
        return x >= originX && x < originX + width && z >= originZ && z < originZ + depth;
    }

    /** 基岩底块、当前块和上一格组成连续三连墙；侧向另需两格可站立空气。 */
    private static boolean isWall(Grid grid, int x, int y, int z) {
        return grid.bedrock(x, y - 1, z)
                && grid.bedrock(x, y, z)
                && grid.bedrock(x, y + 1, z);
    }

    /** 固定按北、南、西、东选择首个可站立侧面，取代上游的玩家距离与 Side Check 分支。 */
    private static int wallSide(Grid grid, int x, int y, int z) {
        for (int i = 0; i < HORIZONTAL.length; i++) {
            int sideX = x + HORIZONTAL[i][0];
            int sideZ = z + HORIZONTAL[i][1];
            if (grid.collision(sideX, y - 1, sideZ)
                    && grid.air(sideX, y, sideZ)
                    && grid.air(sideX, y + 1, sideZ)) {
                return i;
            }
        }
        return -1;
    }

    private static int[] box(int x, int y, int z, int maxX, int maxZ, int type) {
        return new int[]{x, y, z, maxX, maxZ, type};
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | ((long) y & 0xFFFL);
    }
}
