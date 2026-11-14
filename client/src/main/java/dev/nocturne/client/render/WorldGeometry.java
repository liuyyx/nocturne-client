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
    /** 各侧面从底边沿外向绕序排列；只填充朝向相机的外表面，避免透明面重复叠色。 */
    private static final int[][] SIDE_EDGES = {{0, 2}, {2, 3}, {3, 1}, {1, 0}};

    private WorldGeometry() {
    }

    /** 画世界坐标轴对齐包围盒的 12 条边。 */
    public static void box(OverlayDraw draw, WorldProjection projection,
                           double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ, float width, int argb) {
        projectBox(projection, minX, minY, minZ, maxX, maxY, maxZ);
        for (int[] edge : BOX_EDGES) {
            line(BOX_POINTS[edge[0]], BOX_POINTS[edge[1]], draw, width, argb);
        }
    }

    /** 沿盒体真实投影面填充，不再把屏幕外接矩形涂到方块轮廓之外。 */
    public static void silhouette(OverlayDraw draw, WorldProjection projection,
                                  double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ, int argb) {
        fillBox(draw, projection, minX, minY, minZ, maxX, maxY, maxZ, argb, argb, 1);
    }

    /** 从世界坐标底面到顶面分八段渐变；每条色带保持同一方块表面的透视边界。 */
    public static void silhouetteFade(OverlayDraw draw, WorldProjection projection,
                                      double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ,
                                      int topArgb, int bottomArgb) {
        fillBox(draw, projection, minX, minY, minZ, maxX, maxY, maxZ, topArgb, bottomArgb, 8);
    }

    private static void fillBox(OverlayDraw draw, WorldProjection projection,
                                double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ,
                                int topArgb, int bottomArgb, int strips) {
        if (((topArgb | bottomArgb) >>> 24) == 0) return;
        projectBox(projection, minX, minY, minZ, maxX, maxY, maxZ);
        fillFace(draw, BOX_POINTS[0], BOX_POINTS[1], BOX_POINTS[3], BOX_POINTS[2], bottomArgb);
        fillFace(draw, BOX_POINTS[4], BOX_POINTS[6], BOX_POINTS[7], BOX_POINTS[5], topArgb);
        for (int i = 0; i < SIDE_EDGES.length; i++) {
            WorldProjection.ScreenPoint a = BOX_POINTS[SIDE_EDGES[i][0]];
            WorldProjection.ScreenPoint b = BOX_POINTS[SIDE_EDGES[i][1]];
            WorldProjection.ScreenPoint c = BOX_POINTS[SIDE_EDGES[i][1] + 4];
            WorldProjection.ScreenPoint d = BOX_POINTS[SIDE_EDGES[i][0] + 4];
            if (!frontFacing(a, b, c, d)) continue;
            for (int strip = 0; strip < strips; strip++) {
                float low = (float) strip / strips;
                float high = (float) (strip + 1) / strips;
                int color = RenderSupport.blend(bottomArgb, topArgb, (low + high) * 0.5f);
                draw.quad(along(a, d, low, true), along(a, d, low, false),
                        along(b, c, low, true), along(b, c, low, false),
                        along(b, c, high, true), along(b, c, high, false),
                        along(a, d, high, true), along(a, d, high, false), color);
            }
        }
    }

    private static void fillFace(OverlayDraw draw, WorldProjection.ScreenPoint a,
                                 WorldProjection.ScreenPoint b, WorldProjection.ScreenPoint c,
                                 WorldProjection.ScreenPoint d, int color) {
        if (frontFacing(a, b, c, d)) {
            draw.quad(a.rawX, a.rawY, b.rawX, b.rawY, c.rawX, c.rawY, d.rawX, d.rawY, color);
        }
    }

    private static boolean frontFacing(WorldProjection.ScreenPoint a, WorldProjection.ScreenPoint b,
                                       WorldProjection.ScreenPoint c, WorldProjection.ScreenPoint d) {
        return a.depth > 0 && b.depth > 0 && c.depth > 0 && d.depth > 0
                && (b.rawX - a.rawX) * (c.rawY - a.rawY)
                - (b.rawY - a.rawY) * (c.rawX - a.rawX) < -0.0001f;
    }

    /** 屏幕坐标按深度还原后插值，避免把透视渐变条当成等距屏幕条而扭曲侧边。 */
    private static float along(WorldProjection.ScreenPoint a, WorldProjection.ScreenPoint b,
                                float t, boolean horizontal) {
        double wa = (1.0 - t) * a.depth, wb = t * b.depth;
        return (float) (((horizontal ? a.rawX : a.rawY) * wa
                + (horizontal ? b.rawX : b.rawY) * wb) / (wa + wb));
    }

    /** 画竖直圆柱的上下圆环与四条母线，用于水晶等竖向盒的低成本近似。 */
    public static void verticalBox(OverlayDraw draw, WorldProjection projection,
                                   double centerX, double minY, double centerZ,
                                   double radius, double height, int segments, float width, int argb) {
        if (segments <= 0) {
            return;
        }
        circle(draw, projection, centerX, minY, centerZ, radius, segments, width, argb);
        circle(draw, projection, centerX, minY + height, centerZ, radius, segments, width, argb);
        for (int i = 0; i < 4; i++) {
            double angle = Math.PI * 0.5 * i;
            double x = centerX + Math.cos(angle) * radius;
            double z = centerZ + Math.sin(angle) * radius;
            line(draw, projection, x, minY, z, x, minY + height, z, width, argb);
        }
    }

    /** 在世界点绘制文字；点不可见时跳过。 */
    public static void label(OverlayDraw draw, WorldProjection projection, String text,
                             double x, double y, double z, float size, int argb) {
        WorldProjection.ScreenPoint point = LINE_POINTS[0];
        projection.project(x, y, z, point);
        if (point.visible) {
            draw.text(text, point.x, point.y, size, argb);
        }
    }

    /** 底面与顶面各按 x/z 的四种组合排列；调用方复用同一组八角。 */
    private static void projectBox(WorldProjection projection,
                                   double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ) {
        projection.project(minX, minY, minZ, BOX_POINTS[0]);
        projection.project(maxX, minY, minZ, BOX_POINTS[1]);
        projection.project(minX, minY, maxZ, BOX_POINTS[2]);
        projection.project(maxX, minY, maxZ, BOX_POINTS[3]);
        projection.project(minX, maxY, minZ, BOX_POINTS[4]);
        projection.project(maxX, maxY, minZ, BOX_POINTS[5]);
        projection.project(minX, maxY, maxZ, BOX_POINTS[6]);
        projection.project(maxX, maxY, maxZ, BOX_POINTS[7]);
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
