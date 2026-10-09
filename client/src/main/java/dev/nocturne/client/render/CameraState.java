package dev.nocturne.client.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.Reflect;
import dev.nocturne.client.mapping.ClassType;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 相机状态读取：把游戏里的眼位 / 朝向 / FOV 反射出来，喂给 {@link WorldProjection}。
 *
 * <p>用的成员在四代里都是同一组规范名（映射表负责翻译）：{@code Entity#getEyePosition} 拿眼位
 * （返回 {@code Vec3}，再读 {@code Vec3#x/y/z}）、{@code Entity#yRot}/{@code #xRot} 拿朝向、
 * {@code Options#fov} 拿视场角。1.8.9 的朝向字段名不同，由别名桥接过去（见
 * {@code tools/mapping/aliases-1.8.9.toml}），所以这里不写任何版本分支。
 *
 * <p>缺任何一项就返回 {@code false}（例如玩家还没进世界、或某版本确实没有该成员）——调用方据此
 * 跳过本帧覆盖物绘制，而不是拿一份零值相机去投出一堆乱线。
 *
 * <p>线程安全：每帧在渲染线程调用一次，不做同步。
 */
public final class CameraState {

    /** 本类负责刷新的投影。 */
    private final WorldProjection projection;

    /** 是否已经就缺成员打过一次诊断（避免每帧刷屏）。 */
    private boolean missingLogged;

    /**
     * @param projection 要刷新的投影对象（由调用方持有并复用）
     */
    public CameraState(WorldProjection projection) {
        this.projection = projection;
    }

    /**
     * 读取相机状态并刷新投影。
     *
     * @param bridge 游戏桥；为 {@code null} 或游戏未就绪时返回 {@code false}
     * @param width  视口宽（逻辑像素）
     * @param height 视口高（逻辑像素）
     * @return 是否成功刷新（{@code false} 表示本帧不该画覆盖物）
     */
    public boolean update(GameBridge bridge, int width, int height) {
        if (bridge == null || width <= 0 || height <= 0) {
            return false;
        }
        Object player = bridge.player();
        if (player == null) {
            return false;
        }
        Object eye = bridge.callMapped(player, ClassType.ENTITY, "getEyePosition");
        if (eye == null) {
            return missing(bridge, "Entity#getEyePosition");
        }
        Double eyeX = number(bridge.readField(eye, ClassType.VEC3, "x"));
        Double eyeY = number(bridge.readField(eye, ClassType.VEC3, "y"));
        Double eyeZ = number(bridge.readField(eye, ClassType.VEC3, "z"));
        if (eyeX == null || eyeY == null || eyeZ == null) {
            return missing(bridge, "Vec3#x/y/z");
        }
        Double yaw = number(bridge.readField(player, ClassType.ENTITY, "yRot"));
        Double pitch = number(bridge.readField(player, ClassType.ENTITY, "xRot"));
        if (yaw == null || pitch == null) {
            return missing(bridge, "Entity#yRot/xRot");
        }
        Object minecraft = bridge.minecraft();
        Object options = minecraft == null
                ? null
                : bridge.readField(minecraft, ClassType.MINECRAFT, "options");
        Double fov = options == null ? null : fovOf(bridge.readField(options, ClassType.OPTIONS, "fov"));
        if (fov == null) {
            return missing(bridge, "Options#fov");
        }
        projection.update(eyeX, eyeY, eyeZ, yaw.floatValue(), pitch.floatValue(), fov.floatValue(),
                width, height);
        return true;
    }

    /** @return 当帧投影（由 {@link #update} 刷新） */
    public WorldProjection projection() {
        return projection;
    }

    /** 把读到的值转成 {@code Double}；不是数字（含 {@code null}）时返回 {@code null}。 */
    static Double number(Object value) {
        return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
    }

    /**
     * 读视场角：1.8.9–1.16.4 的 {@code Options#fov} 是 {@code int}，1.16.5 起是
     * {@code OptionInstance<Integer>}。后者直接当数字读会拿到 {@code null}——覆盖层于是整帧不画，
     * 而日志只留一句"cannot read Options#fov"（26.3 实测就是这个）。
     *
     * @param raw {@code Options#fov} 的原始读数（可能是数字，也可能是 {@code OptionInstance}）
     * @return 视场角；两种形态都取不到时返回 {@code null}
     */
    static Double fovOf(Object raw) {
        Double direct = number(raw);
        if (direct != null || raw == null) {
            return direct;
        }
        Method getter = getters.computeIfAbsent(raw.getClass(),
                type -> Optional.ofNullable(Reflect.method(type, "get"))).orElse(null);
        return getter == null ? null : number(Reflect.call(getter, raw));
    }

    /** {@code OptionInstance.get()} 按运行期类缓存（{@code Optional.empty()} = 该类没有 get()）。 */
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> getters = new ConcurrentHashMap<>();

    /**
     * 缺成员时的统一处理：打一次诊断并返回 {@code false}。
     *
     * <p>诊断里带上版本与成员名——"覆盖物一帧都没画"最常见的原因就是某个版本没有这个成员
     * （例如 1.8.9 的朝向字段名不同），不说清楚就只能靠猜。
     */
    private boolean missing(GameBridge bridge, String member) {
        if (!missingLogged) {
            missingLogged = true;
            System.out.println("[nocturne] world overlay: cannot read " + member
                    + " on this version; overlay drawing is off (mapping=" + bridge.mapping().describe()
                    + ")");
        }
        return false;
    }
}
