package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldGeometry;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** TNT 引信标签与危险半径；上游球面填充改为水平圆环及薄层投影剪影，不读写 GL。 */
public final class TntTimerModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final NumberValue dangerRadius = add(new NumberValue("Danger Radius", 7, .5, 12, .1));
    private final NumberValue lethalRadius = add(new NumberValue("Lethal Radius", 4, .5, 12, .1));
    private final NumberValue outerThickness = add(new NumberValue("Outer Thickness", .12, .01, 1, .01));
    private final NumberValue innerThickness = add(new NumberValue("Inner Thickness", .16, .01, 1, .01));
    private final NumberValue alpha = add(new NumberValue("Alpha", 16, 16, 255, 1));
    private final NumberValue segments = add(new NumberValue("Segments", 360, 16, 360, 1));
    private final NumberValue sphereRings = add(new NumberValue("Sphere Rings", 10, 2, 64, 1));
    private final BooleanValue filledSphere = add(new BooleanValue("Filled Sphere", true));
    private final BooleanValue wireSphere = add(new BooleanValue("Wireframe Sphere", false));
    private final BooleanValue pulse = add(new BooleanValue("Pulse Near Explosion", true));
    private final NumberValue pulseThreshold = add(new NumberValue("Pulse Threshold", 2, .2, 10, .1));
    private final NumberValue pulseAmplitude = add(new NumberValue("Pulse Amplitude", .12, 0, .5, .01));
    private final NumberValue pulseSpeed = add(new NumberValue("Pulse Speed", 2, .2, 8, .1));
    private final Map<Object, Timer> cache = new IdentityHashMap<Object, Timer>();
    private final List<Timer> active = new ArrayList<Timer>();
    private final double[] position = new double[3];
    private final WorldProjection.ScreenPoint bottom = new WorldProjection.ScreenPoint();
    private final WorldProjection.ScreenPoint top = new WorldProjection.ScreenPoint();
    private GameBridge tickBridge;
    private int epoch;

    public TntTimerModule() { super("TNT Timer", Category.RENDER); }

    @Override
    protected void onStart() { listen(WorldChangeEvent.class, event -> clear()); }

    @Override
    protected void onStop() { clear(); }

    private void clear() { cache.clear(); active.clear(); }

    @Override
    public void onTick() {
        active.clear();
        tickBridge = bridge();
        if (tickBridge == null) return;
        epoch++;
        RenderSupport.forEachEntity(tickBridge, this);
        if (cache.size() != active.size()) {
            java.util.Iterator<Timer> iterator = cache.values().iterator();
            while (iterator.hasNext()) if (iterator.next().seen != epoch) iterator.remove();
        }
    }

    @Override
    public void visit(Object entity) {
        if (!tickBridge.isInstance(entity, ClassType.PRIMED_TNT)
                || !tickBridge.entityPosition(entity, position)) return;
        Timer timer = cache.get(entity);
        if (timer == null) { timer = new Timer(); cache.put(entity, timer); }
        timer.x = position[0]; timer.y = position[1]; timer.z = position[2];
        timer.height = tickBridge.entityHeight(entity);
        int fuse = Math.max(0, tickBridge.tntFuse(entity));
        if (fuse != timer.fuse) {
            timer.fuse = fuse;
            timer.text = String.format(Locale.ROOT, "%.1fs", fuse / 20.0);
        }
        timer.maximum = Math.max(timer.maximum, fuse);
        timer.seen = epoch;
        active.add(timer);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        double seconds = System.nanoTime() / 1_000_000_000.0;
        int count = sphereRings.asInt(), sides = segments.asInt();
        for (int i = 0; i < active.size(); i++) {
            Timer timer = active.get(i);
            position[0] = timer.x; position[1] = timer.y; position[2] = timer.z;
            float life = Math.max(0f, Math.min(1f, (float) timer.fuse / timer.maximum));
            int rgb = life < .5f ? RenderSupport.blend(0xFFFF4040, 0xFFFFFF40, life * 2)
                    : RenderSupport.blend(0xFFFFFF40, 0xFF40FF40, (life - .5f) * 2);
            int tint = (alpha.asInt() << 24) | (rgb & 0xFFFFFF);
            int fillTint = RenderSupport.withAlpha(tint, 1f / count);
            // alpha 最低为 16，但高环数均分后仍可能被 round 成 0；保留最低一层可见度。
            if ((fillTint >>> 24) == 0) fillTint = (tint & 0xFFFFFF) | 0x01000000;
            double pulseScale = pulse.get() && timer.fuse / 20.0 < pulseThreshold.get()
                    ? 1 + pulseAmplitude.get() * Math.sin(Math.PI * 2 * pulseSpeed.get() * seconds) : 1;
            double radius = dangerRadius.get() * pulseScale;
            double lethal = lethalRadius.get() * pulseScale;
            double centerY = position[1] + .5;
            float outerWidth = Math.max(.5f, outerThickness.asFloat() * 10);
            float innerWidth = Math.max(.5f, innerThickness.asFloat() * 10);
            // 约六屏幕像素一段；Segments 仍是上限，远处不再向游戏绘制 API 发数百条不可分辨线段。
            projection.project(position[0], centerY, position[2], bottom);
            projection.project(position[0] + radius, centerY, position[2], top);
            float screenRadius = bottom.visible && top.visible ? Math.abs(top.x - bottom.x) : 48f;
            int drawnSides = Math.min(sides, Math.max(16,
                    (int) Math.ceil(Math.PI * 2 * screenRadius / 6.0)));
            WorldGeometry.circle(draw, projection, position[0], position[1] + .01, position[2],
                    radius, drawnSides, outerWidth, tint);
            WorldGeometry.circle(draw, projection, position[0], position[1] + .02, position[2],
                    lethal, drawnSides, innerWidth, tint);
            if (filledSphere.get() || wireSphere.get()) {
                for (int ring = 0; ring < count; ring++) {
                    double fraction = -1 + 2.0 * (ring + .5) / count;
                    double y = centerY + radius * fraction;
                    double horizontalRadius = radius * Math.sqrt(Math.max(0, 1 - fraction * fraction));
                    if (filledSphere.get()) {
                        WorldGeometry.silhouette(draw, projection,
                                position[0] - horizontalRadius, y - outerThickness.get() * .5,
                                position[2] - horizontalRadius, position[0] + horizontalRadius,
                                y + outerThickness.get() * .5, position[2] + horizontalRadius,
                                fillTint);
                    }
                    if (wireSphere.get()) {
                        WorldGeometry.circle(draw, projection, position[0], y, position[2],
                                horizontalRadius, drawnSides, outerWidth, tint);
                    }
                }
            }
            projection.project(position[0], position[1], position[2], bottom);
            projection.project(position[0], position[1] + timer.height + .3, position[2], top);
            if (!top.visible) continue;
            // 整数逻辑字号限制动态距离下的字形缓存数量，同时让度量与绘制使用完全相同的尺寸。
            float size = bottom.visible ? Math.max(4, Math.min(32,
                    Math.round(16 * Math.abs(top.y - bottom.y) / (Math.max(.5, timer.height) * 32)))) : 8;
            draw.text(timer.text, top.x - draw.textWidth(timer.text, size) * .5f,
                    top.y - draw.textHeight(size), size, rgb);
        }
    }

    private static final class Timer {
        private double x, y, z, height;
        private int fuse = Integer.MIN_VALUE;
        private int maximum = 80;
        private int seen;
        private String text = "";
    }
}
