package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldProjection;

import java.util.function.Consumer;

/** 世界覆盖层共享的实体遍历与屏外方向指示。 */
public final class RenderSupport {
    private RenderSupport() {
    }

    public static void forEachEntity(GameBridge bridge, Consumer<Object> consumer) {
        Object entities = bridge.entities();
        if (entities instanceof Iterable<?>) {
            for (Object entity : (Iterable<?>) entities) {
                consumer.accept(entity);
            }
        } else if (entities instanceof Object[]) {
            for (Object entity : (Object[]) entities) {
                consumer.accept(entity);
            }
        }
    }

    public static boolean onScreen(WorldProjection.ScreenPoint point, OverlayDraw draw) {
        return point.visible && point.x >= 0.0f && point.x <= draw.width()
                && point.y >= 0.0f && point.y <= draw.height();
    }

    /** 以相对朝向 0=正前方绘制椭圆环上的箭头，并可选显示文本。 */
    public static void indicator(OverlayDraw draw, float relativeDegrees, float radiusScale,
                                 int color, String text) {
        float centerX = draw.width() * 0.5f;
        float centerY = draw.height() * 0.5f;
        float radiusX = draw.width() * radiusScale * 0.5f;
        float radiusY = draw.height() * radiusScale * 0.5f;
        double angle = Math.toRadians(relativeDegrees);
        float x = (float) (centerX + Math.sin(angle) * radiusX);
        float y = (float) (centerY - Math.cos(angle) * radiusY);
        float left = x - 4f;
        float right = x + 4f;
        draw.line(left, y + 4f, x, y - 4f, 1.5f, color);
        draw.line(x, y - 4f, right, y + 4f, 1.5f, color);
        draw.line(left, y + 4f, right, y + 4f, 1.5f, color);
        if (text != null && !text.isEmpty()) {
            draw.text(text, x - draw.textWidth(text) * 0.5f, y + 8f, color);
        }
    }

    /** 世界点处的屏幕十字标记。 */
    public static void marker(OverlayDraw draw, WorldProjection projection,
                              double x, double y, double z, int color) {
        WorldProjection.ScreenPoint point = projection.project(x, y, z);
        if (!point.visible) {
            return;
        }
        draw.line(point.x - 4f, point.y, point.x + 4f, point.y, 1.0f, color);
        draw.line(point.x, point.y - 4f, point.x, point.y + 4f, 1.0f, color);
    }
}
