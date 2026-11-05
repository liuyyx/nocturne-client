package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;

import java.util.Locale;

/** 显示 TNT 引信倒计时与可切换的爆炸范围环。 */
public final class ExplosionsModule extends GameModule implements WorldOverlay {
    private final BooleanValue blastRing = add(new BooleanValue("Blast ring", true));

    public ExplosionsModule() {
        super("Explosions", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        RenderSupport.forEachEntity(bridge, entity -> {
            if (!bridge.isInstance(entity, ClassType.PRIMED_TNT)) {
                return;
            }
            double[] position = bridge.entityPosition(entity);
            WorldProjection.ScreenPoint point = projection.project(position[0], position[1] + 0.5, position[2]);
            if (!point.visible) {
                return;
            }
            String text = String.format(Locale.ROOT, "%.1fs", bridge.tntFuse(entity) / 20.0);
            draw.text(text, point.x - draw.textWidth(text) * 0.5f, point.y - 8f, 0xFFFF5040);
            if (blastRing.get()) {
                ring(draw, point.x, point.y, Math.max(6f, 40f / (float) Math.max(1.0, point.depth)), 0xFFFFFF40);
            }
        });
    }

    private static void ring(OverlayDraw draw, float x, float y, float radius, int color) {
        float previousX = x + radius;
        float previousY = y;
        for (int i = 1; i <= 24; i++) {
            double angle = i * Math.PI * 2.0 / 24.0;
            float nextX = (float) (x + Math.cos(angle) * radius);
            float nextY = (float) (y + Math.sin(angle) * radius);
            draw.line(previousX, previousY, nextX, nextY, 1.0f, color);
            previousX = nextX;
            previousY = nextY;
        }
    }
}
