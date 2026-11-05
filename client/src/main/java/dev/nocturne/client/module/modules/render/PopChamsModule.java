package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.List;

/** PopChams：图腾事件生成上浮/缩放/淡出的玩家线框残影，保留全部 Easing 选项。 */
public final class PopChamsModule extends GameModule implements WorldOverlay {
    private final BooleanValue ignoreSelf = add(new BooleanValue("Ignore Self", true));
    private final NumberValue renderTime = add(new NumberValue("Render Time", 1, 0.1, 6, 0.1));
    private final NumberValue yModifier = add(new NumberValue("Y Modifier", 0.75, -4, 4, 0.05));
    private final ModeValue yEasing = add(new ModeValue("Y Easing", "EASE_IN_OUT_EXPO",
            "LINEAR", "DECELERATE", "SMOOTH_STEP", "EASE_IN_QUAD", "EASE_OUT_QUAD", "EASE_IN_OUT_QUAD",
            "EASE_IN_CUBIC", "EASE_OUT_CUBIC", "EASE_IN_OUT_CUBIC", "EASE_IN_QUART", "EASE_OUT_QUART",
            "EASE_IN_OUT_QUART", "EASE_IN_QUINT", "EASE_OUT_QUINT", "EASE_IN_OUT_QUINT", "EASE_IN_SINE",
            "EASE_OUT_SINE", "EASE_IN_OUT_SINE", "EASE_IN_EXPO", "EASE_OUT_EXPO", "EASE_IN_OUT_EXPO",
            "EASE_IN_CIRC", "EASE_OUT_CIRC", "EASE_IN_OUT_CIRC", "SIGMOID", "EASE_OUT_ELASTIC",
            "EASE_IN_BACK", "EASE_OUT_BACK", "DYNAMIC_ISLAND"));
    private final NumberValue scaleModifier = add(new NumberValue("Scale Modifier", -0.25, -4, 4, 0.05));
    private final BooleanValue fadeOut = add(new BooleanValue("Fade Out", true));
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0x19FFFFFF));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0x7FFFFFFF));
    private final List<Ghost> ghosts = new ArrayList<Ghost>();

    public PopChamsModule() {
        super("Pop Chams", Category.RENDER);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, event -> {
            if (event.direction != PacketEvent.Direction.RECEIVE) {
                return;
            }
            int[] data = bridge().entityEventData(event.packet);
            if (data == null || data[0] != 35) {
                return;
            }
            RenderSupport.forEachEntity(bridge(), entity -> {
                if (bridge().entityId(entity) == data[1] && (!ignoreSelf.get() || entity != bridge().player())) {
                    double[] pos = bridge().entityPosition(entity);
                    if (pos != null) {
                        ghosts.add(new Ghost(pos, System.currentTimeMillis()));
                    }
                }
            });
        });
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        long now = System.currentTimeMillis();
        ghosts.removeIf(ghost -> now - ghost.created > renderTime.get() * 1000.0);
        for (Ghost ghost : ghosts) {
            float progress = (now - ghost.created) / (renderTime.asFloat() * 1000.0f);
            float eased = easing(progress);
            double scale = Math.max(0.05, 1.0 + scaleModifier.get() * eased);
            int color = lineColor.argb();
            if (fadeOut.get()) {
                color = color & 0xFFFFFF | (int) ((color >>> 24) * (1.0f - progress)) << 24;
            }
            double y = ghost.pos[1] + yModifier.get() * eased;
            WorldGeometry.box(draw, projection, ghost.pos[0] - 0.3 * scale, y, ghost.pos[2] - 0.3 * scale,
                    ghost.pos[0] + 0.3 * scale, y + 1.8 * scale, ghost.pos[2] + 0.3 * scale,
                    1.0f, color);
            WorldGeometry.circle(draw, projection, ghost.pos[0], y + 1.6 * scale, ghost.pos[2],
                    0.25 * scale, 12, 1.0f, color);
        }
    }

    private float easing(float x) {
        x = Math.max(0.0f, Math.min(1.0f, x));
        String mode = yEasing.get();
        if (mode.equals("LINEAR")) return x;
        if (mode.equals("DECELERATE")) return 1.0f - (x - 1.0f) * (x - 1.0f);
        if (mode.equals("SMOOTH_STEP")) return (float) (-2.0 * Math.pow(x, 3) + 3.0 * Math.pow(x, 2));
        if (mode.contains("QUAD")) return inOut(x, x * x, mode);
        if (mode.contains("CUBIC")) return inOut(x, x * x * x, mode);
        if (mode.contains("QUART")) return inOut(x, (float) Math.pow(x, 4), mode);
        if (mode.contains("QUINT")) return inOut(x, (float) Math.pow(x, 5), mode);
        if (mode.contains("SINE")) return inOut(x, (float) (1.0 - Math.cos(x * Math.PI * 0.5)), mode);
        if (mode.contains("EXPO")) return inOut(x, x == 0.0f ? 0.0f : (float) Math.pow(2, 10 * x - 10), mode);
        if (mode.contains("CIRC")) return inOut(x, (float) (1.0 - Math.sqrt(1.0 - x * x)), mode);
        if (mode.equals("SIGMOID")) return (float) (1.0 / (1.0 + Math.exp(-x)));
        if (mode.equals("EASE_OUT_ELASTIC")) return (float) (Math.pow(2, -10 * x)
                * Math.sin((x * 10 - 0.75) * Math.PI * 2.0 / 3.0) * 0.5 + 1.0);
        if (mode.equals("EASE_IN_BACK")) return 2.70158f * x * x * x - 1.70158f * x * x;
        if (mode.equals("EASE_OUT_BACK")) return (float) (1.0 + 2.70158 * Math.pow(x - 1.0, 3)
                + 1.70158 * Math.pow(x - 1.0, 2));
        return (float) (1.0 - Math.cos(x * Math.PI * (0.2 + 2.5 * Math.pow(x, 3))) * Math.exp(-x * 5));
    }

    private static float inOut(float x, float value, String mode) {
        if (mode.contains("IN_OUT")) {
            return x < 0.5f ? value * 2.0f : 1.0f - value * 0.5f;
        }
        if (mode.startsWith("EASE_OUT")) {
            return 1.0f - value;
        }
        return value;
    }

    @Override
    protected void onStop() {
        ghosts.clear();
    }

    private static final class Ghost {
        private final double[] pos;
        private final long created;

        private Ghost(double[] pos, long created) {
            this.pos = pos;
            this.created = created;
        }
    }
}
