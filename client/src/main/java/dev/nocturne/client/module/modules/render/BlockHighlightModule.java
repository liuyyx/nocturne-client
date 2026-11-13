package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldGeometry;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.Locale;

/** 方块与命中面的投影高亮；上游面填充降级成四角投影包围矩形，法向缺失则回退整格。 */
public final class BlockHighlightModule extends GameModule implements WorldOverlay {
    private static final int[] EDGE_ORDER = {0, 1, 3, 2, 0};
    private final ModeValue mode = add(new ModeValue("Mode", "Outline",
            "Both", "BothSide", "Fill", "FilledSide", "Outline", "OutlinedSide"));
    private final ColorValue color = add(new ColorValue("Color", 0x64FFFFFF));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0xFFFFFFFF));
    private final NumberValue lineWidth = add(new NumberValue("Line Width", 2, 0, 5, .5));
    private final WorldProjection.ScreenPoint[] points = new WorldProjection.ScreenPoint[4];
    private boolean hit;
    private boolean warned;
    private int x, y, z, normalX, normalY, normalZ;
    private Object lastDirection;

    public BlockHighlightModule() {
        super("Block Highlight", Category.RENDER);
        for (int i = 0; i < points.length; i++) points[i] = new WorldProjection.ScreenPoint();
    }

    @Override
    protected void onStop() { hit = false; lastDirection = null; normalX = normalY = normalZ = 0; }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        hit = false;
        if (bridge == null || actions == null) return;
        Object picked = actions.pick(4.5, 1f);
        int[] coordinates = bridge.hitBlockPosition(picked);
        if (coordinates == null) {
            if (!actions.isAvailable("pick") && !warned) {
                warned = true;
                System.out.println("[nocturne] Block Highlight: pick unavailable on this version (action binding missing)");
            }
            return;
        }
        hit = true;
        x = coordinates[0]; y = coordinates[1]; z = coordinates[2];
        Object direction = bridge.hitDirection(picked);
        if (direction == lastDirection) return;
        lastDirection = direction;
        normalX = normalY = normalZ = 0;
        Object dx = bridge.callMapped(direction, ClassType.DIRECTION, "getStepX");
        Object dy = bridge.callMapped(direction, ClassType.DIRECTION, "getStepY");
        Object dz = bridge.callMapped(direction, ClassType.DIRECTION, "getStepZ");
        if (dx instanceof Number && dy instanceof Number && dz instanceof Number) {
            normalX = ((Number) dx).intValue(); normalY = ((Number) dy).intValue(); normalZ = ((Number) dz).intValue();
        } else if (direction != null) {
            String face = String.valueOf(direction).toLowerCase(Locale.ROOT);
            if ("east".equals(face)) normalX = 1; else if ("west".equals(face)) normalX = -1;
            else if ("up".equals(face)) normalY = 1; else if ("down".equals(face)) normalY = -1;
            else if ("south".equals(face)) normalZ = 1; else if ("north".equals(face)) normalZ = -1;
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!hit) return;
        boolean fill = mode.is("Both") || mode.is("BothSide") || mode.is("Fill") || mode.is("FilledSide");
        boolean outline = !mode.is("Fill") && !mode.is("FilledSide") && lineWidth.get() > 0;
        boolean side = mode.is("BothSide") || mode.is("FilledSide") || mode.is("OutlinedSide");
        if (!side || (normalX == 0 && normalY == 0 && normalZ == 0)) {
            if (fill) WorldGeometry.silhouette(draw, projection, x, y, z, x + 1, y + 1, z + 1, color.argb());
            if (outline) WorldGeometry.box(draw, projection, x, y, z, x + 1, y + 1, z + 1,
                    lineWidth.asFloat(), lineColor.argb());
            return;
        }
        float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double px, py, pz;
            if (normalX != 0) {
                px = x + (normalX > 0 ? 1 : 0); py = y + (i & 1); pz = z + ((i >> 1) & 1);
            } else if (normalY != 0) {
                px = x + (i & 1); py = y + (normalY > 0 ? 1 : 0); pz = z + ((i >> 1) & 1);
            } else {
                px = x + (i & 1); py = y + ((i >> 1) & 1); pz = z + (normalZ > 0 ? 1 : 0);
            }
            projection.project(px, py, pz, points[i]);
            if (!points[i].visible) continue;
            left = Math.min(left, points[i].x); top = Math.min(top, points[i].y);
            right = Math.max(right, points[i].x); bottom = Math.max(bottom, points[i].y);
        }
        if (fill && left != Float.POSITIVE_INFINITY) draw.rect(left, top, right - left, bottom - top, color.argb());
        if (outline) {
            for (int i = 0; i < 4; i++) {
                WorldProjection.ScreenPoint first = points[EDGE_ORDER[i]], second = points[EDGE_ORDER[i + 1]];
                if (first.visible && second.visible) draw.line(first.x, first.y, second.x, second.y,
                        lineWidth.asFloat(), lineColor.argb());
            }
        }
    }
}
