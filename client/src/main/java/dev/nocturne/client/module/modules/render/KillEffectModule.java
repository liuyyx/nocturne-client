package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldGeometry;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import java.util.concurrent.atomic.AtomicReference;

/** 目标离开实体列表时播放自绘效果；离开视距亦会触发，并不证明击杀。不会生成原版实体或播放音效。 */
public final class KillEffectModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final BooleanValue lightning = add(new BooleanValue("Lightning", true));
    private final BooleanValue explosion = add(new BooleanValue("Explosion", true));
    private final BooleanValue squid = add(new BooleanValue("Squid", true));
    private final BooleanValue blood = add(new BooleanValue("Blood", true));
    private final AtomicReference<Object> attacked = new AtomicReference<Object>();
    private final double[][] effects = new double[32][4];
    private final double[] position = new double[3];
    private int next;
    private int targetId = -1;
    private Object target;
    private Object world;
    private boolean found;
    private boolean positioned;
    private double x, y, z;
    private GameBridge tickBridge;

    public KillEffectModule() { super("Kill Effect", Category.RENDER); }
    @Override protected void onStart() {
        listen(AttackEvent.class, event -> { if (event.post && !event.cancelled && event.target != null) attacked.set(event.target); });
        listen(WorldChangeEvent.class, event -> clear());
    }
    @Override protected void onStop() { clear(); }
    private void clear() {
        attacked.set(null); target = null; targetId = -1; positioned = false; world = null;
        for (int i = 0; i < effects.length; i++) effects[i][3] = 0;
    }
    @Override public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null || bridge.world() == null) { clear(); return; }
        if (world != bridge.world()) { clear(); world = bridge.world(); }
        Object latest = attacked.getAndSet(null);
        if (latest != null) {
            int id = bridge.entityId(latest);
            if (id >= 0) { target = latest; targetId = id; positioned = bridge.entityPosition(latest, position); if (positioned) { x = position[0]; y = position[1]; z = position[2]; } }
        }
        if (targetId < 0 || bridge.entities() == null) return;
        found = false; tickBridge = bridge;
        try { RenderSupport.forEachEntity(bridge, this); } finally { tickBridge = null; }
        if (!found) {
            if (positioned) { double[] e = effects[next++ & 31]; e[0] = x; e[1] = y; e[2] = z; e[3] = System.currentTimeMillis(); }
            targetId = -1; target = null; positioned = false;
        }
    }
    @Override public void visit(Object entity) {
        if (entity != target || tickBridge.entityId(entity) != targetId) return;
        found = true;
        if (tickBridge.entityPosition(entity, position)) { x = position[0]; y = position[1]; z = position[2]; positioned = true; }
    }
    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < effects.length; i++) {
            double[] e = effects[i]; if (e[3] == 0) continue;
            double t = Math.max(0, (now - e[3]) / 1000.0); if (t >= 1) continue;
            if (lightning.get() && t < .3) {
                int color = RenderSupport.withAlpha(0xFFFFF7AA, (float) (1 - t / .3));
                for (int j = 0; j < 6; j++) WorldGeometry.line(draw, projection,
                        e[0] + ((j & 1) == 0 ? -.18 : .18), e[1] + j * .65, e[2],
                        e[0] + ((j & 1) == 0 ? .18 : -.18), e[1] + (j + 1) * .65, e[2], 2f, color);
            }
            if (explosion.get() && t < .5) {
                int color = RenderSupport.withAlpha(0xFFFFAA55, (float) (1 - t / .5));
                for (int j = 0; j < 3; j++) WorldGeometry.circle(draw, projection, e[0], e[1] + j * .12, e[2], t * (2 + j), 24, 1.5f, color);
            }
            if (squid.get() && t < .45) WorldGeometry.label(draw, projection, "☠", e[0], e[1] + t * 2, e[2], 14f, RenderSupport.withAlpha(0xFFFFFFFF, (float) (1 - t / .45)));
            if (blood.get()) for (int j = 0; j < 6; j++) {
                double a = j * Math.PI / 3;
                WorldGeometry.label(draw, projection, "♥", e[0] + Math.cos(a) * t, e[1] + t * 1.5, e[2] + Math.sin(a) * t, 10f, RenderSupport.withAlpha(0xFFFF3333, (float) (1 - t)));
            }
        }
    }
}
