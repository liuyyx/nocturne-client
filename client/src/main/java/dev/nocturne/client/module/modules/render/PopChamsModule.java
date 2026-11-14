package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.PacketEvent;
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
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;
import java.util.concurrent.atomic.AtomicReference;

/** 图腾触发后绘制玩家包围盒快照；无法替换实体模型，剪影并非原始玩家模型。 */
public final class PopChamsModule extends GameModule implements WorldOverlay {
    private final BooleanValue ignoreSelf = add(new BooleanValue("Ignore Self", true));
    private final NumberValue renderTime = add(new NumberValue("Render Time", 1, .1, 6, .1));
    private final NumberValue yModifier = add(new NumberValue("Y Modifier", .75, -4, 4, .05));
    private final NumberValue scaleModifier = add(new NumberValue("Scale Modifier", -.25, -4, 4, .05));
    private final BooleanValue fadeOut = add(new BooleanValue("Fade Out", true));
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0x19FFFFFF));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0x7FFFFFFF));
    private final AtomicReference<State> state = new AtomicReference<State>(
            new State(new Object(), new Snapshot[0]));

    public PopChamsModule() { super("Pop Chams", Category.RENDER); }
    @Override protected void onStart() {
        listen(PacketEvent.class, this::packet);
        listen(WorldChangeEvent.class, event -> clear());
    }
    @Override protected void onStop() { clear(); }
    private void clear() {
        state.set(new State(new Object(), new Snapshot[0]));
    }
    private void packet(PacketEvent event) {
        if (event.direction != PacketEvent.Direction.RECEIVE) return;
        final GameBridge bridge = bridge();
        if (bridge == null || !bridge.isInstance(event.packet, ClassType.CLIENTBOUND_ENTITY_EVENT)) return;
        int[] data = bridge.entityEventData(event.packet);
        if (data == null || data.length < 2 || data[0] != 35 || data[1] < 0) return;
        final int wantedId = data[1];
        final Object generation = state.get().generation;
        RenderSupport.forEachEntity(bridge, new RenderSupport.EntityVisitor() {
            @Override public void visit(Object entity) {
                if (bridge.entityId(entity) != wantedId || !bridge.isInstance(entity, ClassType.PLAYER)
                        || (ignoreSelf.get() && RenderSupport.isSelf(bridge, entity))) return;
                double[] position = new double[3];
                if (!bridge.entityPosition(entity, position)) return;
                Snapshot snapshot = new Snapshot(position[0], position[1], position[2],
                        bridge.entityWidth(entity), bridge.entityHeight(entity), System.currentTimeMillis());
                publish(generation, snapshot);
            }
        });
    }

    /** 仅在事件线程创建快照，CAS 避免绘制线程等待，并防止世界切换后写入旧快照。 */
    private void publish(Object generation, Snapshot snapshot) {
        for (;;) {
            State previous = state.get();
            if (previous.generation != generation) return;
            Snapshot[] old = previous.snapshots;
            int kept = Math.min(old.length, 31);
            Snapshot[] updated = new Snapshot[kept + 1];
            System.arraycopy(old, old.length - kept, updated, 0, kept);
            updated[kept] = snapshot;
            if (state.compareAndSet(previous, new State(generation, updated))) return;
        }
    }
    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
            Snapshot[] snapshots = state.get().snapshots;
            long now = System.currentTimeMillis();
            double duration = renderTime.get() * 1000;
            for (int i = 0; i < snapshots.length; i++) {
                Snapshot s = snapshots[i];
                if (s == null) continue;
                double progress = Math.max(0, (now - s.startMs) / duration);
                if (progress >= 1) continue;
                double factor = Math.max(0, 1 + scaleModifier.get() * progress);
                double radius = s.width * factor * .5;
                double y = s.y + yModifier.get() * progress;
                double height = s.height * factor;
                float alpha = fadeOut.get() ? (float) (1 - progress) : 1f;
                WorldGeometry.silhouette(draw, projection, s.x-radius, y, s.z-radius,
                        s.x+radius, y+height, s.z+radius, RenderSupport.withAlpha(sideColor.argb(), alpha));
                WorldGeometry.box(draw, projection, s.x-radius, y, s.z-radius,
                        s.x+radius, y+height, s.z+radius, 1f, RenderSupport.withAlpha(lineColor.argb(), alpha));
            }
    }
    private static final class State {
        final Object generation;
        final Snapshot[] snapshots;
        State(Object generation, Snapshot[] snapshots) {
            this.generation = generation;
            this.snapshots = snapshots;
        }
    }
    private static final class Snapshot {
        final double x, y, z, width, height;
        final long startMs;
        Snapshot(double x, double y, double z, double width, double height, long startMs) {
            this.x = x; this.y = y; this.z = z;
            this.width = width; this.height = height; this.startMs = startMs;
        }
    }
}
