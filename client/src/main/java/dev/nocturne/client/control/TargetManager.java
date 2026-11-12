package dev.nocturne.client.control;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * 共享目标选择器：AimAssist / KillAura / 后续状态机只描述“要什么”，筛选与排序集中在这里。
 *
 * <p>目标来自当前世界的实体渲染列表；只把真实存在的活体实体交出去。距离与视野都按玩家
 * 眼睛到实体脚部中心计算。好友过滤是内建且不可关闭的：打架误伤好友比“少一个目标”代价大得多；
 * 视线过滤默认开启（{@link Options#lineOfSight}），模块不再各自调 hasLineOfSight/好友表。
 * AntiBot 只对玩家目标求值——NPC 判定对动物/怪物没有意义，还会误杀合法刷怪目标。
 */
public final class TargetManager {

    /** 目标排序方式。 */
    public enum Sort {
        /** 水平视角差最小。 */
        ANGLE,
        /** 距离最近。 */
        DISTANCE,
        /** 血量最低。 */
        HEALTH
    }

    /** 一次查询的过滤条件；可复用以避免每 tick 分配。 */
    public static final class Options {
        public double range = 5.0;
        public double maxAngle = 180.0;
        public boolean players = true;
        public boolean mobs = false;
        public boolean invisibles = false;
        /** 是否要求与服务端一致的视线（{@code LivingEntity#hasLineOfSight}）；默认开。 */
        public boolean lineOfSight = true;
        public Sort sort = Sort.ANGLE;
    }

    /** 一个已通过过滤的目标快照；实体对象只在本 tick 内有效。 */
    public static final class Target {
        private final Object entity;
        private final double x;
        private final double y;
        private final double z;
        private final double distance;
        private final double angle;
        private final double health;

        private Target(Object entity, double x, double y, double z,
                       double distance, double angle, double health) {
            this.entity = entity;
            this.x = x;
            this.y = y;
            this.z = z;
            this.distance = distance;
            this.angle = angle;
            this.health = health;
        }

        public Object entity() {
            return entity;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public double distance() {
            return distance;
        }

        /** 相对玩家当前 yaw 的水平夹角，0..180。 */
        public double angle() {
            return angle;
        }

        public double health() {
            return health;
        }

        /** 瞄准点：脚部中心上方 1.2 格，作为没有实体高度映射时的稳定近似。 */
        public double aimY() {
            return y + 1.2;
        }
    }

    /** 找最优目标；世界未就绪、实体列表缺失或无候选时返回 {@code null}。 */
    public Target find(NocturneClient client, Options options) {
        List<Target> targets = list(client, options);
        return targets.isEmpty() ? null : targets.get(0);
    }

    /** 返回按 {@link Options#sort} 排序后的全部候选；不会返回 {@code null}。 */
    public List<Target> list(NocturneClient client, Options options) {
        if (client == null || options == null) {
            return Collections.emptyList();
        }
        GameBridge bridge = client.gameBridge();
        if (bridge == null) {
            return Collections.emptyList();
        }
        Object player = bridge.player();
        Object minecraft = bridge.minecraft();
        if (player == null || minecraft == null) {
            return Collections.emptyList();
        }
        Object world = bridge.readField(minecraft, ClassType.MINECRAFT, "level");
        if (world == null) {
            return Collections.emptyList();
        }
        double[] origin = position(bridge, player);
        if (origin == null) {
            return Collections.emptyList();
        }
        Object yawValue = bridge.readField(player, ClassType.ENTITY, "yRot");
        if (!(yawValue instanceof Number)) {
            return Collections.emptyList();
        }
        float yaw = ((Number) yawValue).floatValue();
        Object entities = bridge.callMapped(world, ClassType.CLIENT_LEVEL, "entitiesForRendering");
        if (entities == null) {
            return Collections.emptyList();
        }

        List<Target> found = new ArrayList<Target>();
        collect(bridge, player, entities, origin, yaw, options, found);
        Collections.sort(found, comparator(options.sort));
        return found;
    }

    private void collect(GameBridge bridge, Object player, Object entities, double[] origin,
                         float yaw, Options options, List<Target> out) {
        if (entities instanceof Iterable<?>) {
            Iterator<?> iterator = ((Iterable<?>) entities).iterator();
            while (iterator.hasNext()) {
                consider(bridge, player, iterator.next(), origin, yaw, options, out);
            }
            return;
        }
        if (entities instanceof Object[]) {
            for (Object entity : (Object[]) entities) {
                consider(bridge, player, entity, origin, yaw, options, out);
            }
        }
    }

    private void consider(GameBridge bridge, Object player, Object entity, double[] origin,
                          float yaw, Options options, List<Target> out) {
        if (entity == null || entity == player
                || !bridge.isInstance(entity, ClassType.LIVING_ENTITY)) {
            return;
        }
        boolean isPlayer = bridge.isInstance(entity, ClassType.PLAYER);
        if (isPlayer && !options.players) {
            return;
        }
        if (!isPlayer && !options.mobs) {
            return;
        }
        // 好友判定只对玩家生效：友方玩家永不做目标；集中在这里，aura 模块不再各自过滤。
        if (isPlayer && Friends.isFriend(bridge, entity)) {
            return;
        }
        if (options.lineOfSight && !lineOfSight(bridge, player, entity)) {
            return;
        }
        Object healthValue = bridge.callMapped(entity, ClassType.LIVING_ENTITY, "getHealth");
        if (!(healthValue instanceof Number)) {
            return;
        }
        double health = ((Number) healthValue).doubleValue();
        if (!(health > 0.0)) {
            return;
        }
        if (!options.invisibles
                && Boolean.TRUE.equals(bridge.callMapped(entity, ClassType.ENTITY, "isInvisible"))) {
            return;
        }
        double[] position = position(bridge, entity);
        if (position == null) {
            return;
        }
        double dx = position[0] - origin[0];
        double dy = position[1] - origin[1];
        double dz = position[2] - origin[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > options.range) {
            return;
        }
        float targetYaw = RotationController.yawTo(dx, dz);
        double angle = Math.abs(RotationController.wrap(targetYaw - yaw));
        if (angle > Math.max(0.0, options.maxAngle) * 0.5) {
            return;
        }
        out.add(new Target(entity, position[0], position[1], position[2],
                distance, angle, health));
    }

    /**
     * 视线判定；映射缺失时按“可见”处理。
     *
     * <p>用 `callMapped` 而不是 {@code bridge.hasLineOfSight} 是为了区分“明确不可见”(false) 与
     * “该版本读不到”(null)——把读不到当成不可见会让整个目标系统在缺成员的版本上静默无目标。
     */
    private static boolean lineOfSight(GameBridge bridge, Object player, Object entity) {
        Object visible = bridge.callMapped(player, ClassType.LIVING_ENTITY, "hasLineOfSight", entity);
        return !Boolean.FALSE.equals(visible);
    }

    /** 读取实体脚部坐标；任一轴缺失时返回 {@code null}。 */
    static double[] position(GameBridge bridge, Object entity) {
        Object position = bridge.callMapped(entity, ClassType.ENTITY, "position");
        if (position == null) {
            return null;
        }
        Object x = bridge.readField(position, ClassType.VEC3, "x");
        Object y = bridge.readField(position, ClassType.VEC3, "y");
        Object z = bridge.readField(position, ClassType.VEC3, "z");
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) {
            return null;
        }
        double px = ((Number) x).doubleValue();
        double py = ((Number) y).doubleValue();
        double pz = ((Number) z).doubleValue();
        if (!finite(px) || !finite(py) || !finite(pz)) {
            return null;
        }
        return new double[]{px, py, pz};
    }

    private static Comparator<Target> comparator(final Sort sort) {
        return new Comparator<Target>() {
            @Override
            public int compare(Target first, Target second) {
                if (sort == Sort.DISTANCE) {
                    return Double.compare(first.distance, second.distance);
                }
                if (sort == Sort.HEALTH) {
                    return Double.compare(first.health, second.health);
                }
                return Double.compare(first.angle, second.angle);
            }
        };
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
