package dev.nocturne.client.render;

/** 世界覆盖层常用的线条几何；投影暂存对象只在渲染线程复用。 */
public final class WorldGeometry {
    private static final WorldProjection.ScreenPoint[] BOX_POINTS = points(8);
    private static final WorldProjection.ScreenPoint[] LINE_POINTS = points(2);
    private static WorldProjection.ScreenPoint[] circlePoints = points(65);
    private static final int[][] BOX_EDGES = {
            {0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3},
            {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}
    };

    private WorldGeometry() {
    }

    /** 画世界坐标轴对齐包围盒的 12 条边。 */
    public static void box(OverlayDraw draw, WorldProjection projection,
                           double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ, float width, int argb) {
        // 底面与顶面各按 x/z 的四种组合排列。
        projection.project(minX, minY, minZ, BOX_POINTS[0]);
        projection.project(maxX, minY, minZ, BOX_POINTS[1]);
        projection.project(minX, minY, maxZ, BOX_POINTS[2]);
        projection.project(maxX, minY, maxZ, BOX_POINTS[3]);
        projection.project(minX, maxY, minZ, BOX_POINTS[4]);
        projection.project(maxX, maxY, minZ, BOX_POINTS[5]);
        projection.project(minX, maxY, maxZ, BOX_POINTS[6]);
        projection.project(maxX, maxY, maxZ, BOX_POINTS[7]);
        for (int[] edge : BOX_EDGES) {
            line(BOX_POINTS[edge[0]], BOX_POINTS[edge[1]], draw, width, argb);
        }
    }

    /** 画水平圆环；segments 是闭合环的线段数。 */
    public static void circle(OverlayDraw draw, WorldProjection projection,
                              double x, double y, double z, double radius,
                              int segments, float width, int argb) {
        if (segments <= 0) {
            return;
        }
        int count = segments + 1;
        if (circlePoints.length < count) {
            circlePoints = points(count);
        }
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2.0 * (i % segments) / segments;
            projection.project(x + Math.cos(angle) * radius, y,
                    z + Math.sin(angle) * radius, circlePoints[i]);
        }
        for (int i = 0; i < segments; i++) {
            line(circlePoints[i], circlePoints[i + 1], draw, width, argb);
        }
    }

    /** 画两个世界坐标点之间的线段；任一端不可见时跳过。 */
    public static void line(OverlayDraw draw, WorldProjection projection,
                            double x1, double y1, double z1,
                            double x2, double y2, double z2, float width, int argb) {
        projection.project(x1, y1, z1, LINE_POINTS[0]);
        projection.project(x2, y2, z2, LINE_POINTS[1]);
        line(LINE_POINTS[0], LINE_POINTS[1], draw, width, argb);
    }

    private static void line(WorldProjection.ScreenPoint first, WorldProjection.ScreenPoint second,
                             OverlayDraw draw, float width, int argb) {
        if (first.visible && second.visible) {
            draw.line(first.x, first.y, second.x, second.y, width, argb);
        }
    }

    private static WorldProjection.ScreenPoint[] points(int count) {
        WorldProjection.ScreenPoint[] result = new WorldProjection.ScreenPoint[count];
        for (int i = 0; i < count; i++) {
            result[i] = new WorldProjection.ScreenPoint();
        }
        return result;
    }
}
