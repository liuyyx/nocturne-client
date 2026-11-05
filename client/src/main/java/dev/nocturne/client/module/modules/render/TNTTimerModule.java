package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** TNT Timer：引信倒计时、危险/致命半径球环和临爆脉冲。 */
public final class TNTTimerModule extends GameModule implements WorldOverlay {
    private final NumberValue dangerRadius = add(new NumberValue("Danger Radius", 7, 0.5, 12, 0.5));
    private final NumberValue lethalRadius = add(new NumberValue("Lethal Radius", 4, 0.5, 12, 0.5));
    private final NumberValue outerThickness = add(new NumberValue("Outer Thickness", 0.12, 0.01, 1, 0.01));
    private final NumberValue innerThickness = add(new NumberValue("Inner Thickness", 0.16, 0.01, 1, 0.01));
    private final NumberValue alpha = add(new NumberValue("Alpha", 16, 16, 255, 1));
    private final NumberValue segments = add(new NumberValue("Segments", 360, 16, 360, 1));
    private final NumberValue sphereRings = add(new NumberValue("Sphere Rings", 10, 2, 64, 1));
    private final BooleanValue filledSphere = add(new BooleanValue("Filled Sphere", true));
    private final BooleanValue wireframeSphere = add(new BooleanValue("Wireframe Sphere", false));
    private final BooleanValue pulse = add(new BooleanValue("Pulse Near Explosion", true));
    private final NumberValue pulseThreshold = add(new NumberValue("Pulse Threshold (s)", 2, 0.2, 10, 0.1));
    private final NumberValue pulseAmplitude = add(new NumberValue("Pulse Amplitude", 0.12, 0, 0.5, 0.01));
    private final NumberValue pulseSpeed = add(new NumberValue("Pulse Speed (Hz)", 2, 0.2, 8, 0.1));

    public TNTTimerModule() {
        super("TNT Timer", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (!bridge().isInstance(entity, ClassType.PRIMED_TNT)) {
                return;
            }
            double[] pos = bridge().entityPosition(entity);
            if (pos == null) {
                return;
            }
            int fuse = bridge().tntFuse(entity);
            float seconds = fuse / 20.0f;
            float ratio = Math.max(0.0f, Math.min(1.0f, fuse / 80.0f));
            int color = 0xFF000000 | (int) (255 * (1.0 - ratio)) << 16 | (int) (255 * ratio) << 8;
            double pulseScale = pulse.get() && seconds <= pulseThreshold.get()
                    ? 1.0 + Math.sin(System.currentTimeMillis() / 1000.0 * Math.PI * 2.0
                    * pulseSpeed.get()) * pulseAmplitude.get() : 1.0;
            int rings = sphereRings.asInt();
            for (int ring = 0; ring < rings; ring++) {
                double y = pos[1] + ring / Math.max(1.0, rings - 1.0) * 0.2;
                WorldGeometry.circle(draw, projection, pos[0], y, pos[2], dangerRadius.get() * pulseScale,
                        segments.asInt(), outerThickness.asFloat(), color & 0xFFFFFF | alpha.asInt() << 24);
                if (filledSphere.get() || wireframeSphere.get() && (ring == 0 || ring == rings - 1)) {
                    WorldGeometry.circle(draw, projection, pos[0], y, pos[2], lethalRadius.get() * pulseScale,
                            segments.asInt(), innerThickness.asFloat(), color);
                }
            }
            WorldGeometry.label(draw, projection, String.format(java.util.Locale.ROOT, "%.1fs", seconds),
                    pos[0], pos[1] + 1.2, pos[2], color);
        });
    }
}
