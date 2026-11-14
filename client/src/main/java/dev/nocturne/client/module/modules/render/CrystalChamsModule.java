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
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 水晶采用六段竖向盒与盒形剪影近似；没有渲染替换钩子，因此不会隐藏原版水晶。 */
public final class CrystalChamsModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0x46A078FF));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0xDCE6DCFF));
    private final NumberValue scale = add(new NumberValue("Scale", 1, .1, 3, .05));
    private final NumberValue lineWidth = add(new NumberValue("Line Width", 2, .5, 5, .5));
    private final List<double[]> positions = new ArrayList<double[]>();
    private final double[] position = new double[3];
    private GameBridge tickBridge;
    private int count;
    private final AtomicInteger generation = new AtomicInteger();
    private volatile int publishedGeneration = -1;

    public CrystalChamsModule() { super("Crystal Chams", Category.RENDER); }
    @Override protected void onStart() { listen(WorldChangeEvent.class, event -> clear()); }
    @Override protected void onStop() { clear(); }
    private void clear() { generation.incrementAndGet(); }

    @Override public void onTick() {
        int currentGeneration = generation.get();
        try {
            count = 0;
            tickBridge = bridge();
            if (tickBridge != null) RenderSupport.forEachEntity(tickBridge, this);
            publishedGeneration = currentGeneration;
        } finally { tickBridge = null; }
    }
    @Override public void visit(Object entity) {
        if (!tickBridge.isInstance(entity, ClassType.END_CRYSTAL)
                || !tickBridge.entityPosition(entity, position)) return;
        if (count == positions.size()) positions.add(new double[3]);
        double[] target = positions.get(count++);
        target[0] = position[0]; target[1] = position[1]; target[2] = position[2];
    }
    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (publishedGeneration != generation.get()) return;
            double radius = .98 * scale.get();
            for (int i = 0; i < count; i++) {
                double[] p = positions.get(i);
                double bottom = p[1] - radius;
                WorldGeometry.silhouette(draw, projection, p[0]-radius, bottom, p[2]-radius,
                        p[0]+radius, bottom+2*radius, p[2]+radius, sideColor.argb());
                WorldGeometry.verticalBox(draw, projection, p[0], bottom, p[2], radius,
                        2*radius, 6, lineWidth.asFloat(), lineColor.argb());
            }

    }
}
