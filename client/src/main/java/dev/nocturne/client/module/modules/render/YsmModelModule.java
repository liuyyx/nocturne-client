package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bedrock 立方体线框覆盖层，并非 YSM 模型替换：不支持二进制解码、材质、骨骼层级、
 * 原版模型隐藏或真实 OpenYsm 动画。骨骼摆动仅绕立方体顶部近似。
 * Custom Path 与 Bone Filter 由文本设置输入。
 * 自定义文件在路径或模式变化时异步读取；读取或解析失败时不绘制模型。
 */
public final class YsmModelModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private static final int[] EDGES = {0,1, 0,2, 0,4, 1,3, 1,5, 2,3, 2,6, 3,7, 4,5, 4,6, 5,7, 6,7};
    private static final AtomicLong LAST_ERROR = new AtomicLong();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "nocturne-ysm-loader");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final ModeValue model = add(new ModeValue("Model", "Auto", "Auto", "Alice", "Boqijiang", "ToyotaAe86", "Yatuoya", "Custom"));
    private final TextValue path = add(new TextValue("Custom Path", "", 1024));
    private final NumberValue scale = add(new NumberValue("Scale", 1, .1, 5, .05));
    private final ModeValue animation = add(new ModeValue("Animation", "Vanilla", "Vanilla", "OpenYsm"));
    private final TextValue filter = add(new TextValue("Bone Filter", "", 256));
    private final BooleanValue characterOnly = add(new BooleanValue("Character Only", true));
    private final BooleanValue showItem = add(new BooleanValue("Show Item", true));
    private final NumberValue itemScale = add(new NumberValue("Item Scale", 1, .1, 5, .05));
    private final BooleanValue flipLeft = add(new BooleanValue("Flip Left Walk", false));
    private final BooleanValue flipRight = add(new BooleanValue("Flip Right Walk", false));
    private final BooleanValue flipOther = add(new BooleanValue("Flip Other Anim", false));
    private final WorldProjection.ScreenPoint[] points = new WorldProjection.ScreenPoint[8];
    private final WorldProjection.ScreenPoint label = new WorldProjection.ScreenPoint();
    private final double[] position = new double[3];
    private final double[] velocity = new double[3];
    private final ArrayList<Snapshot> snapshots = new ArrayList<Snapshot>();
    private int snapshotCount;
    private Object snapshotWorld;
    private final AtomicLong loadGeneration = new AtomicLong();
    private volatile LoadResult loaded;
    private YsmGeometry geometry = YsmGeometry.builtin("Auto");
    private String loadedModel = "Auto", loadedPath = "";
    private String filterText = "", filterNeedle = "";
    private boolean filterExclude;
    private GameBridge tickBridge;

    private static final class Snapshot {
        double x, y, z, yaw, ticks;
        boolean moving;
        String itemName;
    }

    private static final class LoadResult {
        final long generation;
        final YsmGeometry geometry;
        LoadResult(long generation, YsmGeometry geometry) {
            this.generation = generation;
            this.geometry = geometry;
        }
    }

    public YsmModelModule() {
        super("Ysm Model", Category.RENDER);
        for (int i = 0; i < points.length; i++) points[i] = new WorldProjection.ScreenPoint();
    }

    @Override protected void onStart() {
        listen(WorldChangeEvent.class, event -> clearState());
    }

    @Override public void onTick() {
        GameBridge bridge = bridge();
        Object world = bridge == null ? null : bridge.world();
        if (world != snapshotWorld) {
            clearState();
            snapshotWorld = world;
        }
        if (world == null) return;
        String selected = model.get(), selectedPath = path.get();
        if (!selected.equals(loadedModel) || !selectedPath.equals(loadedPath)) {
            loadedModel = selected; loadedPath = selectedPath;
            final long generation = loadGeneration.incrementAndGet();
            loaded = null;
            if ("Custom".equals(selected)) {
                geometry = null;
                final String requestedPath = selectedPath;
                LOADER.execute(new Runnable() {
                    @Override public void run() {
                        if (generation != loadGeneration.get()) return;
                        YsmGeometry result = null;
                        try {
                            result = YsmGeometry.parse(new String(
                                    Files.readAllBytes(Paths.get(requestedPath)), StandardCharsets.UTF_8));
                        } catch (Exception ex) {
                            long now = System.currentTimeMillis(), previous = LAST_ERROR.get();
                            if (now - previous >= 5000 && LAST_ERROR.compareAndSet(previous, now)) {
                                System.err.println("[Nocturne] Ysm Model 无法读取 " + requestedPath + ": " + ex);
                            }
                        }
                        if (generation == loadGeneration.get()) loaded = new LoadResult(generation, result);
                    }
                });
            } else geometry = YsmGeometry.builtin(selected);
        }
        LoadResult result = loaded;
        if (result != null && result.generation == loadGeneration.get()) {
            geometry = result.geometry;
            loaded = null;
        }
        String currentFilter = filter.get();
        if (!currentFilter.equals(filterText)) {
            filterText = currentFilter;
            filterExclude = currentFilter.startsWith("!");
            filterNeedle = filterExclude ? currentFilter.substring(1) : currentFilter;
        }
        snapshotCount = 0;
        tickBridge = bridge;
        try {
            RenderSupport.forEachEntity(bridge, this);
        } finally {
            tickBridge = null;
        }
        for (int i = snapshotCount; i < snapshots.size(); i++) snapshots.get(i).itemName = null;
    }

    private void clearState() {
        loadGeneration.incrementAndGet();
        loaded = null;
        loadedModel = null;
        geometry = null;
        snapshotCount = 0;
        snapshotWorld = null;
        tickBridge = null;
        for (int i = 0; i < snapshots.size(); i++) snapshots.get(i).itemName = null;
    }

    @Override protected void onStop() { clearState(); }

    /** 只在 tick 遍历时读取实体；快照不持有实体或物品对象。 */
    @Override public void visit(Object entity) {
        GameBridge bridge = tickBridge;
        if (bridge == null || !bridge.isInstance(entity, ClassType.LIVING_ENTITY)
                || (characterOnly.get() && !bridge.isInstance(entity, ClassType.PLAYER))
                || (RenderSupport.isSelf(bridge, entity) && bridge.perspective() == 0)
                || !bridge.entityPosition(entity, position)) return;
        Object yawValue = bridge.readField(entity, ClassType.ENTITY, "yRot");
        Object tickValue = bridge.readField(entity, ClassType.ENTITY, "tickCount");
        bridge.entityVelocity(entity, velocity);
        if (snapshotCount == snapshots.size()) snapshots.add(new Snapshot());
        Snapshot snapshot = snapshots.get(snapshotCount++);
        snapshot.x = position[0]; snapshot.y = position[1]; snapshot.z = position[2];
        snapshot.yaw = yawValue instanceof Number ? ((Number) yawValue).doubleValue() : 0;
        snapshot.ticks = tickValue instanceof Number ? ((Number) tickValue).doubleValue() : 0;
        snapshot.moving = velocity[0] * velocity[0] + velocity[2] * velocity[2] > 1e-6;
        snapshot.itemName = null;
        if (showItem.get()) {
            Object item = bridge.heldItem(entity, false);
            if (item != null) snapshot.itemName = bridge.itemDisplayName(item);
        }
    }

    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (geometry == null) return;
        for (int i = 0; i < snapshotCount; i++) drawSnapshot(draw, projection, snapshots.get(i));
    }

    private void drawSnapshot(OverlayDraw draw, WorldProjection projection, Snapshot snapshot) {
        double yaw = Math.toRadians(snapshot.yaw);
        double yawCos = Math.cos(yaw), yawSin = Math.sin(yaw);
        double ticks = snapshot.ticks;
        double swing = Math.sin(ticks * .25) * (snapshot.moving ? .35 : .05) * (animation.is("OpenYsm") ? 1 : .5);
        double unit = scale.get() / 16;
        for (int c = 0; c < geometry.cubes.length; c++) {
            YsmGeometry.Cube cube = geometry.cubes[c];
            if (!YsmGeometry.matches(cube.bone, filterNeedle, filterExclude, filterText.isEmpty())) continue;
            boolean left = cube.bone.contains("left"), right = cube.bone.contains("right");
            double angle = swing * (right ? -1 : 1);
            if (left ? flipLeft.get() : right ? flipRight.get() : flipOther.get()) angle = -angle;
            double cs = Math.cos(angle), sn = Math.sin(angle);
            double pivot = cube.y + cube.height;
            for (int i = 0; i < 8; i++) {
                double x = (cube.x + ((i & 1) == 0 ? 0 : cube.width)) * unit;
                double y = (cube.y + ((i & 2) == 0 ? 0 : cube.height) - pivot) * unit;
                double z = (cube.z + ((i & 4) == 0 ? 0 : cube.depth)) * unit;
                double animatedY = y * cs - z * sn + pivot * unit;
                double animatedZ = y * sn + z * cs;
                projection.project(snapshot.x + x * yawCos - animatedZ * yawSin,
                        snapshot.y + animatedY, snapshot.z + x * yawSin + animatedZ * yawCos, points[i]);
            }
            for (int i = 0; i < EDGES.length; i += 2) {
                WorldProjection.ScreenPoint a = points[EDGES[i]], b = points[EDGES[i + 1]];
                if (a.visible && b.visible) draw.line(a.x, a.y, b.x, b.y, 1f, cube.color);
            }
        }
        if (showItem.get()) {
            String name = snapshot.itemName;
            if (name != null && !name.isEmpty()) {
                double offset = itemScale.get();
                projection.project(snapshot.x + .35 * offset, snapshot.y + offset, snapshot.z, label);
                if (label.visible) draw.text(name, label.x, label.y, 8f, 0xFFFFFFFF);
            }
        }
    }

}
