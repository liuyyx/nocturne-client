package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldGeometry;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.MultiBoolValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Epsilon Trajectories：手持弹射物的预测弹道线与命中点标记。
 *
 * <p>上游降级：穿透/多重射击附魔读取跨版本不可靠，弩恒按单发箭、穿透恒 0；已发射弹道
 * （Fired Projectiles）不做凋灵头颅之外的类型细分；Impact 的面片填充用投影四边形近似
 * 上游的 3D 面片。模拟本体见 {@link ProjectileSimulator}（纯数学，可单测）。
 */
public final class TrajectoriesModule extends GameModule implements WorldOverlay {
    private static final String BOW = "Bow";
    private static final String CROSSBOW = "Crossbow";
    private static final String TRIDENT = "Trident";
    private static final String FISHING_ROD = "Fishing Rod";
    private static final String SNOWBALL = "Snowball";
    private static final String EGG = "Egg";
    private static final String ENDER_PEARL = "Ender Pearl";
    private static final String EXPERIENCE_BOTTLE = "Experience Bottle";
    private static final String SPLASH_POTION = "Splash Potion";
    private static final String LINGERING_POTION = "Lingering Potion";
    private static final String WIND_CHARGE = "Wind Charge";

    private final MultiBoolValue items = add(new MultiBoolValue("Items",
            Arrays.asList(BOW, CROSSBOW, TRIDENT, FISHING_ROD, SNOWBALL, EGG, ENDER_PEARL,
                    EXPERIENCE_BOTTLE, SPLASH_POTION, LINGERING_POTION, WIND_CHARGE),
            Arrays.asList(BOW, CROSSBOW, TRIDENT, FISHING_ROD, SNOWBALL, EGG, ENDER_PEARL,
                    EXPERIENCE_BOTTLE, SPLASH_POTION, LINGERING_POTION, WIND_CHARGE)));
    private final BooleanValue otherPlayers = add(new BooleanValue("Other Players", true));
    private final BooleanValue firedProjectiles = add(new BooleanValue("Fired Projectiles", false));
    private final BooleanValue ignoreWitherSkulls = add(new BooleanValue("Ignore Wither Skulls", true));
    private final NumberValue simulationSteps = add(new NumberValue("Simulation Steps", 500, 0, 5000, 25));
    private final NumberValue ignoreFirstTicks = add(new NumberValue("Ignore First Ticks", 3, 0, 20, 1));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0xF5FF9600));
    private final NumberValue lineWidth = add(new NumberValue("Line Width", 2.0, 0.5, 5.0, 0.25));
    private final BooleanValue impactFill = add(new BooleanValue("Impact Fill", true));
    private final BooleanValue impactOutline = add(new BooleanValue("Impact Outline", true));
    private final ColorValue impactFillColor = add(new ColorValue("Impact Fill Color", 0x2DFF9600));
    private final ColorValue impactOutlineColor = add(new ColorValue("Impact Outline Color", 0xF5FFB437));
    private final BooleanValue positionBoxes = add(new BooleanValue("Render Position Boxes", false));
    private final NumberValue positionBoxSize = add(new NumberValue("Position Box Size", 0.02, 0.01, 0.1, 0.005));
    private final ColorValue positionFillColor = add(new ColorValue("Position Fill Color", 0x1CFF9600));
    private final ColorValue positionOutlineColor = add(new ColorValue("Position Outline Color", 0xB4FFB437));

    /** 已发射弹道的命中检测目标快照；绘制期只读。 */
    private static final class EntityTarget {
        double minX;
        double minY;
        double minZ;
        double maxX;
        double maxY;
        double maxZ;
    }

    /** 一条弹道：点集（xyz 连续存放）+ 命中信息。 */
    private static final class PathData {
        double[] points = new double[3 * 64];
        int count;
        int startIndex;
        boolean blockImpact;
        double impactX;
        double impactY;
        double impactZ;
        int faceAxis = -1;
        int faceSign;
        boolean entityImpact;
        EntityTarget entity;
        final EntityTarget hitTarget = new EntityTarget();
    }

    /** 两组轨迹交替发布；池容量只在弹道数量增长时扩展。 */
    private static final class PathBuffer extends java.util.AbstractList<PathData> {
        private final ArrayList<PathData> pool = new ArrayList<PathData>();
        private int size;

        void reset() {
            for (int i = 0; i < size; i++) {
                pool.get(i).entity = null;
            }
            size = 0;
        }

        PathData acquire() {
            if (size == pool.size()) {
                pool.add(new PathData());
            }
            PathData path = pool.get(size++);
            path.count = 0;
            path.startIndex = 0;
            path.blockImpact = false;
            path.entityImpact = false;
            path.entity = null;
            path.faceAxis = -1;
            path.faceSign = 0;
            return path;
        }

        void discardLast() {
            pool.get(--size).entity = null;
        }

        @Override
        public PathData get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException();
            }
            return pool.get(index);
        }

        @Override
        public int size() {
            return size;
        }
    }

    private final PathBuffer pathBufferA = new PathBuffer();
    private final PathBuffer pathBufferB = new PathBuffer();

    private volatile List<PathData> paths = Collections.emptyList();
    // tick 在帧驱动线程串行执行；复用前已保存上一条弹道的全部坐标。
    private final ProjectileSimulator simulator = new ProjectileSimulator();
    private final BridgeWorldView worldView = new BridgeWorldView();
    private final ArrayList<EntityTarget> targetPool = new ArrayList<EntityTarget>();
    private final ArrayList<EntityTarget> targetScratch = new ArrayList<EntityTarget>();
    private final double[] targetPosition = new double[3];
    private final double[] motionScratch = new double[3];
    private final ArrayList<Object> entityScratch = new ArrayList<Object>();
    private final ArrayList<Object> playersScratch = new ArrayList<Object>();
    // 绘制只在渲染线程执行，投影暂存可跨帧复用。
    private final WorldProjection.ScreenPoint previous = new WorldProjection.ScreenPoint();
    private final WorldProjection.ScreenPoint current = new WorldProjection.ScreenPoint();
    private final WorldProjection.ScreenPoint a0 = new WorldProjection.ScreenPoint();
    private final WorldProjection.ScreenPoint a1 = new WorldProjection.ScreenPoint();
    private final WorldProjection.ScreenPoint a2 = new WorldProjection.ScreenPoint();
    private final WorldProjection.ScreenPoint a3 = new WorldProjection.ScreenPoint();

    public TrajectoriesModule() {
        super("Trajectories", Category.RENDER);
    }

    @Override
    protected void onStart() {
        paths = Collections.emptyList();
        listen(WorldChangeEvent.class, event -> paths = Collections.emptyList());
    }

    @Override
    protected void onStop() {
        paths = Collections.emptyList();
    }

    /** 物品描述 → 弹道类别键；不支持的物品返回 null。 */
    static String kindOfItem(String description) {
        if (description == null || description.isEmpty()) {
            return null;
        }
        String name = description.toLowerCase(Locale.ROOT);
        int cut = Math.max(name.lastIndexOf('.'), name.lastIndexOf(':'));
        name = cut >= 0 ? name.substring(cut + 1) : name;
        if (name.endsWith(".name")) {
            name = name.substring(0, name.length() - ".name".length());
        }
        if (name.equals("bow")) {
            return BOW;
        }
        if (name.equals("crossbow")) {
            return CROSSBOW;
        }
        if (name.equals("trident")) {
            return TRIDENT;
        }
        if (name.equals("fishing_rod")) {
            return FISHING_ROD;
        }
        if (name.equals("snowball")) {
            return SNOWBALL;
        }
        if (name.equals("egg")) {
            return EGG;
        }
        if (name.equals("ender_pearl")) {
            return ENDER_PEARL;
        }
        if (name.equals("experience_bottle")) {
            return EXPERIENCE_BOTTLE;
        }
        if (name.equals("splash_potion")) {
            return SPLASH_POTION;
        }
        if (name.equals("lingering_potion")) {
            return LINGERING_POTION;
        }
        if (name.equals("wind_charge")) {
            return WIND_CHARGE;
        }
        return null;
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || !state.present()) {
            paths = Collections.emptyList();
            return;
        }
        Object world = bridge.world();
        if (world == null) {
            paths = Collections.emptyList();
            return;
        }
        PathBuffer out = paths == pathBufferA ? pathBufferB : pathBufferA;
        out.reset();
        try {
            worldView.bind(bridge, world);
            collectPaths(bridge, worldView, out);
            paths = out;
        } finally {
            entityScratch.clear();
            playersScratch.clear();
            worldView.clear();
        }
    }

    /** 帧驱动线程内逐条重算；实体暂存由调用方在异常路径也清空。 */
    private void collectPaths(GameBridge bridge, ProjectileSimulator.WorldView view, PathBuffer out) {
        Object self = bridge.player();
        List<Object> entities = null;
        List<Object> players = playersScratch;
        bridge.worldPlayers(players);
        for (int i = 0; i < players.size(); i++) {
            Object player = players.get(i);
            boolean isSelf = player == self;
            if (!isSelf && !otherPlayers.get()) {
                continue;
            }
            Object stack = bridge.heldItem(player, false);
            String kind = kindOfItem(bridge.itemDescription(stack));
            if (kind == null || !items.selected(kind)) {
                stack = bridge.heldItem(player, true);
                kind = kindOfItem(bridge.itemDescription(stack));
                if (kind == null || !items.selected(kind)) {
                    continue;
                }
            }
            double[] feet = targetPosition;
            if (!bridge.entityPosition(player, feet)) {
                continue;
            }
            double originY = feet[1] + bridge.entityEyeHeight(player) - 0.1;
            double yaw = number(bridge.readField(player, ClassType.ENTITY, "yRot"));
            double pitch = number(bridge.readField(player, ClassType.ENTITY, "xRot"));

            boolean configured;
            if (kind == FISHING_ROD) {
                simulator.configureFishing(feet[0], originY, feet[2], yaw, pitch);
                configured = true;
            } else {
                ProjectileSimulator.Data data = dataForKind(kind);
                double power = data.power;
                if (kind == BOW) {
                    int ticks = bridge.ticksUsingItem(player);
                    float charge = ProjectileSimulator.bowPower(ticks);
                    if (charge <= 0.1F) {
                        if (!isSelf) {
                            continue;
                        }
                        charge = 1.0F;
                    }
                    power = charge * 3.0;
                } else if (kind == CROSSBOW) {
                    power = 3.15;
                }
                configured = simulator.configureShot(data, power, feet[0], originY, feet[2],
                        yaw, pitch, 0.0, 0.0, 1.0, 0.0, false);
            }
            if (!configured) {
                continue;
            }
            int ignored = isSelf ? ignoreFirstTicks.asInt() : 0;
            if (entities == null) {
                entities = entitiesList(bridge);
            }
            List<EntityTarget> targets = collectTargets(bridge, entities, player, null);
            PathData path = out.acquire();
            simulate(simulator, view, ignored, targets, path);
            if (path.count <= 1) {
                out.discardLast();
            }
        }

        if (firedProjectiles.get()) {
            if (entities == null) {
                entities = entitiesList(bridge);
            }
            for (int i = 0; i < entities.size(); i++) {
                Object entity = entities.get(i);
                ProjectileSimulator.Data data = firedData(bridge, entity);
                if (data == null) {
                    continue;
                }
                double[] position = targetPosition;
                double[] velocity = motionScratch;
                if (!bridge.entityPosition(entity, position)) {
                    continue;
                }
                bridge.entityVelocity(entity, velocity);
                Number tickCount = number(bridge.readField(entity, ClassType.ENTITY, "tickCount"));
                boolean inWater = Boolean.TRUE.equals(
                        bridge.callMapped(entity, ClassType.ENTITY, "isInWater"));
                boolean noGravity = Boolean.TRUE.equals(
                        bridge.callMapped(entity, ClassType.ENTITY, "isNoGravity"));
                if (!simulator.configureFired(data, position[0], position[1], position[2],
                        velocity[0], velocity[1], velocity[2], inWater,
                        tickCount == null ? 0 : tickCount.intValue(), noGravity)) {
                    continue;
                }
                Object owner = bridge.callMapped(entity, ClassType.THROWABLE_PROJECTILE, "getOwner");
                List<EntityTarget> targets = collectTargets(bridge, entities, entity, owner);
                PathData path = out.acquire();
                simulate(simulator, view, 0, targets, path);
                if (path.count <= 1) {
                    out.discardLast();
                }
            }
        }

    }

    /** 供 simulate 的实体命中目标集合；excludeA/excludeB 为射手或弹射物自身及其 owner。 */
    private List<EntityTarget> collectTargets(GameBridge bridge, List<Object> entities,
                                              Object excludeA, Object excludeB) {
        targetScratch.clear();
        for (int i = 0; i < entities.size(); i++) {
            Object entity = entities.get(i);
            if (entity == excludeA || entity == excludeB) {
                continue;
            }
            if (!bridge.entityPosition(entity, targetPosition)) {
                continue;
            }
            double width = bridge.entityWidth(entity);
            double height = bridge.entityHeight(entity);
            int index = targetScratch.size();
            if (index == targetPool.size()) {
                targetPool.add(new EntityTarget());
            }
            EntityTarget target = targetPool.get(index);
            target.minX = targetPosition[0] - width * 0.5 - 1.0;
            target.maxX = targetPosition[0] + width * 0.5 + 1.0;
            target.minY = targetPosition[1] - 1.0;
            target.maxY = targetPosition[1] + height + 1.0;
            target.minZ = targetPosition[2] - width * 0.5 - 1.0;
            target.maxZ = targetPosition[2] + width * 0.5 + 1.0;
            targetScratch.add(target);
        }
        return targetScratch;
    }

    private List<Object> entitiesList(GameBridge bridge) {
        Object entities = bridge.entities();
        List<Object> out = entityScratch;
        out.clear();
        if (entities instanceof Iterable) {
            for (Object entity : (Iterable<?>) entities) {
                if (entity != null) {
                    out.add(entity);
                }
            }
        } else if (entities instanceof Object[]) {
            for (Object entity : (Object[]) entities) {
                if (entity != null) {
                    out.add(entity);
                }
            }
        }
        return out;
    }

    /** 已发射实体的运动表归类；不支持的类型返回 null。 */
    private ProjectileSimulator.Data firedData(GameBridge bridge, Object entity) {
        if (bridge.isInstance(entity, ClassType.THROWN_ENDER_PEARL)) {
            return ProjectileSimulator.ENDER_PEARL;
        }
        if (bridge.isInstance(entity, ClassType.THROWN_EXPERIENCE_BOTTLE)) {
            return ProjectileSimulator.EXPERIENCE_BOTTLE;
        }
        if (bridge.isInstance(entity, ClassType.THROWN_POTION)) {
            return ProjectileSimulator.SPLASH_POTION;
        }
        if (bridge.isInstance(entity, ClassType.THROWN_TRIDENT)) {
            if (Boolean.TRUE.equals(bridge.readField(entity, ClassType.ENTITY, "noPhysics"))) {
                return null;
            }
            return ProjectileSimulator.TRIDENT;
        }
        if (bridge.isInstance(entity, ClassType.ABSTRACT_ARROW)) {
            return ProjectileSimulator.ARROW;
        }
        if (bridge.isInstance(entity, ClassType.WITHER_SKULL)) {
            return ignoreWitherSkulls.get() ? null : ProjectileSimulator.EXPLOSIVE;
        }
        if (bridge.isInstance(entity, ClassType.ABSTRACT_FIREBALL)) {
            return ProjectileSimulator.EXPLOSIVE;
        }
        if (bridge.isInstance(entity, ClassType.FISHING_HOOK)) {
            return ProjectileSimulator.FISHING_BOBBER;
        }
        if (bridge.isInstance(entity, ClassType.SNOWBALL)) {
            return ProjectileSimulator.SNOWBALL;
        }
        if (bridge.isInstance(entity, ClassType.EGG)) {
            return ProjectileSimulator.EGG;
        }
        if (bridge.isInstance(entity, ClassType.THROWABLE_PROJECTILE)) {
            return ProjectileSimulator.SNOWBALL;
        }
        return null;
    }

    private ProjectileSimulator.Data dataForKind(String kind) {
        switch (kind) {
            case TRIDENT:
                return ProjectileSimulator.TRIDENT;
            case SNOWBALL:
                return ProjectileSimulator.SNOWBALL;
            case EGG:
                return ProjectileSimulator.EGG;
            case ENDER_PEARL:
                return ProjectileSimulator.ENDER_PEARL;
            case EXPERIENCE_BOTTLE:
                return ProjectileSimulator.EXPERIENCE_BOTTLE;
            case SPLASH_POTION:
                return ProjectileSimulator.SPLASH_POTION;
            case LINGERING_POTION:
                return ProjectileSimulator.LINGERING_POTION;
            case WIND_CHARGE:
                return ProjectileSimulator.WIND_CHARGE;
            case BOW:
            case CROSSBOW:
            default:
                return ProjectileSimulator.ARROW;
        }
    }

    /** 推进模拟并收集折线点；实体命中由外层 AABB 检查接管（上游 ProjectileUtil 的近似）。 */
    private void simulate(ProjectileSimulator simulator, ProjectileSimulator.WorldView view,
                          int ignoredTicks, List<EntityTarget> targets, PathData path) {
        int limit = simulationSteps.asInt();
        limit = Math.max(1, Math.min(limit <= 0 ? 5000 : limit, 5000));
        append(path, simulator.x(), simulator.y(), simulator.z());
        for (int i = 0; i < limit; i++) {
            ProjectileSimulator.Step step = simulator.tick(view);
            if (step.stop && step.faceAxis < 0 && path.count == 1
                    && step.x == path.points[0] && step.y == path.points[1] && step.z == path.points[2]) {
                break;
            }
            append(path, step.x, step.y, step.z);
            if (step.stop) {
                if (step.faceAxis >= 0) {
                    path.blockImpact = true;
                    path.impactX = step.x;
                    path.impactY = step.y;
                    path.impactZ = step.z;
                    path.faceAxis = step.faceAxis;
                    path.faceSign = step.faceSign;
                }
                break;
            }
            EntityTarget hit = hitTarget(targets, step.x, step.y, step.z);
            if (hit != null) {
                path.entityImpact = true;
                path.hitTarget.minX = hit.minX;
                path.hitTarget.minY = hit.minY;
                path.hitTarget.minZ = hit.minZ;
                path.hitTarget.maxX = hit.maxX;
                path.hitTarget.maxY = hit.maxY;
                path.hitTarget.maxZ = hit.maxZ;
                path.entity = path.hitTarget;
                break;
            }
        }
        path.startIndex = Math.max(0, Math.min(path.count - 2, ignoredTicks));
    }

    /** 点是否落在任一目标扩展盒内（上游 search box inflate(1.0) 的等价判定）。 */
    private static EntityTarget hitTarget(List<EntityTarget> targets, double x, double y, double z) {
        for (int i = 0; i < targets.size(); i++) {
            EntityTarget target = targets.get(i);
            if (x >= target.minX && x <= target.maxX
                    && y >= target.minY && y <= target.maxY
                    && z >= target.minZ && z <= target.maxZ) {
                return target;
            }
        }
        return null;
    }

    private static void append(PathData path, double x, double y, double z) {
        if (path.count * 3 + 3 > path.points.length) {
            double[] grown = new double[path.points.length * 2];
            System.arraycopy(path.points, 0, grown, 0, path.points.length);
            path.points = grown;
        }
        path.points[path.count * 3] = x;
        path.points[path.count * 3 + 1] = y;
        path.points[path.count * 3 + 2] = z;
        path.count++;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        List<PathData> snapshot = paths;
        int color = lineColor.argb();
        float width = lineWidth.asFloat();
        boolean boxes = positionBoxes.get();
        double boxSize = positionBoxSize.get();
        for (int p = 0; p < snapshot.size(); p++) {
            PathData path = snapshot.get(p);
            for (int i = path.startIndex + 1; i < path.count; i++) {
                double x0 = path.points[(i - 1) * 3];
                double y0 = path.points[(i - 1) * 3 + 1];
                double z0 = path.points[(i - 1) * 3 + 2];
                double x1 = path.points[i * 3];
                double y1 = path.points[i * 3 + 1];
                double z1 = path.points[i * 3 + 2];
                projection.project(x0, y0, z0, previous);
                projection.project(x1, y1, z1, current);
                if (previous.visible && current.visible) {
                    draw.line(previous.x, previous.y, current.x, current.y, width, color);
                }
                if (boxes) {
                    WorldGeometry.box(draw, projection, x1 - boxSize, y1 - boxSize, z1 - boxSize,
                            x1 + boxSize, y1 + boxSize, z1 + boxSize, 1.0f, positionOutlineColor.argb());
                    WorldGeometry.silhouette(draw, projection, x1 - boxSize, y1 - boxSize, z1 - boxSize,
                            x1 + boxSize, y1 + boxSize, z1 + boxSize, positionFillColor.argb());
                }
            }
            if (path.blockImpact) {
                drawImpact(draw, projection, path);
            }
            if (path.entityImpact && path.entity != null) {
                EntityTarget target = path.entity;
                if (impactFill.get()) {
                    WorldGeometry.silhouette(draw, projection, target.minX, target.minY, target.minZ,
                            target.maxX, target.maxY, target.maxZ, impactFillColor.argb());
                }
                if (impactOutline.get()) {
                    WorldGeometry.box(draw, projection, target.minX, target.minY, target.minZ,
                            target.maxX, target.maxY, target.maxZ, Math.min(width, 1.5f),
                            impactOutlineColor.argb());
                }
            }
        }
    }

    /** 命中点面片：命中面法向决定薄片朝向，四个角投影后填充 + 描边。 */
    private void drawImpact(OverlayDraw draw, WorldProjection projection, PathData path) {
        double radius = 0.25;
        double x = path.impactX;
        double y = path.impactY;
        double z = path.impactZ;
        if (path.faceAxis == 1) {
            projection.project(x - radius, y, z - radius, a0);
            projection.project(x + radius, y, z - radius, a1);
            projection.project(x + radius, y, z + radius, a2);
            projection.project(x - radius, y, z + radius, a3);
        } else if (path.faceAxis == 2) {
            projection.project(x - radius, y - radius, z, a0);
            projection.project(x + radius, y - radius, z, a1);
            projection.project(x + radius, y + radius, z, a2);
            projection.project(x - radius, y + radius, z, a3);
        } else {
            projection.project(x, y - radius, z - radius, a0);
            projection.project(x, y - radius, z + radius, a1);
            projection.project(x, y + radius, z + radius, a2);
            projection.project(x, y + radius, z - radius, a3);
        }
        // 填充用命中面的投影四边形（背面四个角与正面重合于同一屏幕区域时四边形退化，
        // 因此只用单面四边形近似上游的 addFilledSide）。
        boolean visible = a0.visible && a1.visible && a2.visible && a3.visible;
        if (visible && impactFill.get()) {
            draw.quad(a0.x, a0.y, a1.x, a1.y, a2.x, a2.y, a3.x, a3.y, impactFillColor.argb());
        }
        if (visible && impactOutline.get()) {
            int outline = impactOutlineColor.argb();
            float w = lineWidth.asFloat();
            draw.line(a0.x, a0.y, a1.x, a1.y, w, outline);
            draw.line(a1.x, a1.y, a2.x, a2.y, w, outline);
            draw.line(a2.x, a2.y, a3.x, a3.y, w, outline);
            draw.line(a3.x, a3.y, a0.x, a0.y, w, outline);
        }
        // 深度侧角：向上游 addSideOutline 的厚度感近似，忽略（薄片在屏幕上与正面重合）。
    }

    /** 模块持有的方块/水查询面：走 GameBridge 的方块状态与碰撞（计划缓存已覆盖）。 */
    private static final class BridgeWorldView implements ProjectileSimulator.WorldView {
        private GameBridge bridge;
        private Object world;
        private double minY;
        private boolean unavailableLogged;
        private boolean loadedAbsent;
        private final Object[] loadedArgs = new Object[1];

        BridgeWorldView() {
        }

        BridgeWorldView(GameBridge bridge, Object world) {
            bind(bridge, world);
        }

        void bind(GameBridge bridge, Object world) {
            this.bridge = bridge;
            this.world = world;
            this.minY = worldMinY(bridge, world);
            this.loadedAbsent = bridge.mapping().isMemberAbsent(ClassType.LEVEL, "isLoaded");
            if (loadedAbsent && !unavailableLogged) {
                unavailableLogged = true;
                System.out.println("[nocturne] Trajectories: loaded-chunk check unavailable; simulation disabled");
            }
        }

        void clear() {
            bridge = null;
            world = null;
        }

        @Override
        public boolean solid(double x, double y, double z) {
            Object position = bridge.blockPos((int) Math.floor(x), (int) Math.floor(y),
                    (int) Math.floor(z));
            Object state = bridge.blockStateAt(world, position);
            return state != null && bridge.hasBlockCollision(world, state, position);
        }

        @Override
        public boolean water(double x, double y, double z) {
            Object state = bridge.blockStateAt(world, (int) Math.floor(x), (int) Math.floor(y),
                    (int) Math.floor(z));
            return state != null
                    && bridge.blockDescription(state).indexOf("water") >= 0;
        }

        @Override
        public boolean loaded(double x, double y, double z) {
            return !loadedAbsent && loadedAt(bridge.blockPos((int) Math.floor(x),
                    (int) Math.floor(y), (int) Math.floor(z)));
        }

        private boolean loadedAt(Object position) {
            Object result = null;
            if (position != null) {
                loadedArgs[0] = position;
                try {
                    result = bridge.callMapped(world, ClassType.LEVEL, "isLoaded", loadedArgs);
                } finally {
                    loadedArgs[0] = null;
                }
            }
            if (!(result instanceof Boolean) && !unavailableLogged) {
                unavailableLogged = true;
                System.out.println("[nocturne] Trajectories: loaded-chunk check failed; simulation disabled");
            }
            return Boolean.TRUE.equals(result);
        }

        @Override
        public double minY() {
            return minY;
        }
    }

    private static double worldMinY(GameBridge bridge, Object world) {
        Object value = bridge.callMapped(world, ClassType.LEVEL, "getMinY");
        Number number = value instanceof Number ? (Number) value : null;
        // 未映射时不能假定世界底部为 0；模拟步数已有上限。
        return number == null ? Double.NEGATIVE_INFINITY : number.doubleValue();
    }

    private static double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }
}
