package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Hat：Astolfo/Sexy/Fade 渐变的头顶采样斗笠几何。 */
public final class HatModule extends GameModule implements WorldOverlay {
    private final ModeValue mode = add(new ModeValue("Mode", "Sexy", "Astolfo", "Sexy", "Fade"));
    private final NumberValue points = add(new NumberValue("Points", 30, 3, 180, 1));
    private final NumberValue size = add(new NumberValue("Size", 0.5, 0.1, 3, 0.1));
    private final NumberValue offset = add(new NumberValue("Offset", 2000, 0, 5000, 100));
    private final ColorValue color = add(new ColorValue("Color", 0xFFFFFFFF));
    private final ColorValue secondColor = add(new ColorValue("Second Color", 0xFF000000));
    private final BooleanValue onlyThirdPerson = add(new BooleanValue("Only Third Person", true));

    public HatModule() {
        super("Hat", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        // 人称未知（-1：两个字段面都读不到）时按"不在第一人称"处理，宁可多画也不要整片消失。
        if (onlyThirdPerson.get() && bridge().perspective() == 0) {
            return;
        }
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (!bridge().isInstance(entity, ClassType.PLAYER)) {
                return;
            }
            double[] pos = bridge().entityPosition(entity);
            if (pos == null) {
                return;
            }
            int count = points.asInt();
            double y = pos[1] + bridge().entityHeight(entity) + 0.2;
            WorldProjection.ScreenPoint top = projection.project(pos[0], y + size.get() * 0.6, pos[2]);
            for (int i = 0; i < count; i++) {
                double a = Math.PI * 2.0 * i / count;
                double b = Math.PI * 2.0 * (i + 1) / count;
                int line = gradient(i / (float) count);
                WorldProjection.ScreenPoint p1 = projection.project(pos[0] + Math.cos(a) * size.get(), y,
                        pos[2] + Math.sin(a) * size.get());
                WorldProjection.ScreenPoint p2 = projection.project(pos[0] + Math.cos(b) * size.get(), y,
                        pos[2] + Math.sin(b) * size.get());
                if (top.visible && p1.visible) {
                    draw.line(top.x, top.y, p1.x, p1.y, 1.0f, line);
                }
                if (p1.visible && p2.visible) {
                    draw.line(p1.x, p1.y, p2.x, p2.y, 1.0f, line);
                }
            }
        });
    }

    private int gradient(float amount) {
        float phase = (System.currentTimeMillis() + offset.asFloat()) * 0.001f;
        if (mode.is("Astolfo")) {
            return ParticleOverlay.rainbow((long) (phase * 1000 + amount * 500));
        }
        if (mode.is("Sexy")) {
            return java.awt.Color.HSBtoRGB((float) (0.92 + 0.08 * Math.sin(phase + amount * Math.PI * 2)),
                    0.65f, 1.0f) | 0xFF000000;
        }
        return blend(color.argb(), secondColor.argb(), (float) (0.5 + 0.5 * Math.sin(phase + amount * 4)));
    }

    private static int blend(int a, int b, float t) {
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }
}
