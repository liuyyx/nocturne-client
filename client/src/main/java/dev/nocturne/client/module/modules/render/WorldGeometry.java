package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldProjection;

/** 世界几何的统一投影绘制：所有后端共用同一组边和屏幕坐标。 */
public final class WorldGeometry {
    private static final int[][] EDGES = {
            {0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3},
            {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}
    };

    private WorldGeometry() {
    }

    public static void box(OverlayDraw draw, WorldProjection projection,
                           double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ, float width, int color) {
        float[] x = new float[8];
        float[] y = new float[8];
        boolean[] visible = new boolean[8];
        for (int i = 0; i < 8; i++) {
            WorldProjection.ScreenPoint point = projection.project(
                    (i & 1) == 0 ? minX : maxX,
                    (i & 2) == 0 ? minY : maxY,
                    (i & 4) == 0 ? minZ : maxZ);
            x[i] = point.x;
            y[i] = point.y;
            visible[i] = point.visible;
        }
        for (int[] edge : EDGES) {
            if (visible[edge[0]] && visible[edge[1]]) {
                draw.line(x[edge[0]], y[edge[0]], x[edge[1]], y[edge[1]], width, color);
            }
        }
    }

    public static void block(OverlayDraw draw, WorldProjection projection,
                             int x, int y, int z, float width, int color) {
        box(draw, projection, x, y, z, x + 1.0, y + 1.0, z + 1.0, width, color);
    }

    public static void circle(OverlayDraw draw, WorldProjection projection, double x, double y, double z,
                              double radius, int segments, float width, int color) {
        WorldProjection.ScreenPoint previous = null;
        for (int i = 0; i <= segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            WorldProjection.ScreenPoint point = projection.project(x + Math.cos(angle) * radius, y,
                    z + Math.sin(angle) * radius);
            if (previous != null && previous.visible && point.visible) {
                draw.line(previous.x, previous.y, point.x, point.y, width, color);
            }
            previous = point;
        }
    }

    public static void side(OverlayDraw draw, WorldProjection projection,
                            int x, int y, int z, String face, float width, int color) {
        double[][] corners;
        if ("up".equals(face)) {
            corners = new double[][]{{x, y + 1, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}};
        } else if ("down".equals(face)) {
            corners = new double[][]{{x, y, z}, {x, y, z + 1}, {x + 1, y, z + 1}, {x + 1, y, z}};
        } else if ("north".equals(face)) {
            corners = new double[][]{{x, y, z}, {x + 1, y, z}, {x + 1, y + 1, z}, {x, y + 1, z}};
        } else if ("south".equals(face)) {
            corners = new double[][]{{x, y, z + 1}, {x, y + 1, z + 1}, {x + 1, y + 1, z + 1}, {x + 1, y, z + 1}};
        } else if ("west".equals(face)) {
            corners = new double[][]{{x, y, z}, {x, y, z + 1}, {x, y + 1, z + 1}, {x, y + 1, z}};
        } else {
            corners = new double[][]{{x + 1, y, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}, {x + 1, y, z + 1}};
        }
        WorldProjection.ScreenPoint previous = null;
        for (int i = 0; i <= corners.length; i++) {
            double[] corner = corners[i % corners.length];
            WorldProjection.ScreenPoint point = projection.project(corner[0], corner[1], corner[2]);
            if (previous != null && previous.visible && point.visible) {
                draw.line(previous.x, previous.y, point.x, point.y, width, color);
            }
            previous = point;
        }
    }

    public static void blockFilled(OverlayDraw draw, WorldProjection projection,
                                   int x, int y, int z, float width, int sideColor, int lineColor) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        boolean any = false;
        for (int i = 0; i < 8; i++) {
            WorldProjection.ScreenPoint point = projection.project(x + (i & 1), y + ((i & 2) >> 1),
                    z + ((i & 4) >> 2));
            if (point.visible) {
                minX = Math.min(minX, point.x);
                minY = Math.min(minY, point.y);
                maxX = Math.max(maxX, point.x);
                maxY = Math.max(maxY, point.y);
                any = true;
            }
        }
        if (any && (sideColor >>> 24) != 0) {
            draw.rect(minX, minY, maxX - minX, maxY - minY, sideColor);
        }
        block(draw, projection, x, y, z, width, lineColor);
    }

    public static void entity(GameBridge bridge, OverlayDraw draw, WorldProjection projection,
                              Object entity, float width, int color) {
        double[] pos = bridge.entityPosition(entity);
        Object box = bridge.callMapped(entity, ClassType.ENTITY, "getBoundingBox");
        if (pos == null || box == null) {
            return;
        }
        Number minX = number(bridge.readField(box, ClassType.AABB, "minX"));
        Number minY = number(bridge.readField(box, ClassType.AABB, "minY"));
        Number minZ = number(bridge.readField(box, ClassType.AABB, "minZ"));
        Number maxX = number(bridge.readField(box, ClassType.AABB, "maxX"));
        Number maxY = number(bridge.readField(box, ClassType.AABB, "maxY"));
        Number maxZ = number(bridge.readField(box, ClassType.AABB, "maxZ"));
        if (minX == null || minY == null || minZ == null || maxX == null || maxY == null || maxZ == null) {
            double half = bridge.entityWidth(entity) * 0.5;
            box(draw, projection, pos[0] - half, pos[1], pos[2] - half,
                    pos[0] + half, pos[1] + bridge.entityHeight(entity), pos[2] + half, width, color);
            return;
        }
        box(draw, projection, minX.doubleValue(), minY.doubleValue(), minZ.doubleValue(),
                maxX.doubleValue(), maxY.doubleValue(), maxZ.doubleValue(), width, color);
    }

    public static void label(OverlayDraw draw, WorldProjection projection,
                             String text, double x, double y, double z, int color) {
        WorldProjection.ScreenPoint point = projection.project(x, y, z);
        if (!point.visible) {
            return;
        }
        float width = draw.textWidth(text);
        draw.roundedRect(point.x - width * 0.5f - 3f, point.y - draw.textHeight() - 3f,
                width + 6f, draw.textHeight() + 5f, 3f, 0xA00D1014);
        draw.text(text, point.x - width * 0.5f, point.y - draw.textHeight(), color);
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }
}
