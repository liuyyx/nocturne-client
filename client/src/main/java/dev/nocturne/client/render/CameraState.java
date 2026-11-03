package dev.nocturne.client.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;

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
     * 是否按 {@code partialTicks} 插值朝向。
     *
     * <p>默认开启（与游戏的插值渲染对齐，消除框相对画面的 20Hz 阶梯）。排查「开覆盖层后画面/人物
     * 乱窜」时可用 {@code -Dnocturne.debug.noCameraInterp} 关掉二分：关掉之后相机退回 tick 末值，
     * 覆盖层会重新出现轻微抖动，但任何由插值引入的异常都会消失。
     */
    private static final boolean INTERPOLATE =
            System.getProperty("nocturne.debug.noCameraInterp") == null;

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
        Object minecraft = bridge.minecraft();
        // 渲染插值量：游戏按 partialTicks 在「上一个 tick」与「当前 tick」之间插值渲染，覆盖层若直接用
        // tick 末值，60-120fps 的画面里框会以 20Hz 阶梯抖动（真机表现就是「框在甩、像加速」）。
        // 1.13–1.20 从 Minecraft.pausePartialTick 取；更老/更新的版本拿不到就退回 0（等同 tick 末值）。
        float partialTicks = 0f;
        if (minecraft != null) {
            Double raw = number(bridge.readField(minecraft, ClassType.MINECRAFT, "pausePartialTick"));
            if (raw != null && raw.doubleValue() >= 0d && raw.doubleValue() <= 1d) {
                partialTicks = raw.floatValue();
            }
        }
        Object eye = null;
        if (partialTicks > 0f) {
            // 有插值量时**优先**带参重载：1.16.5 的零参 getEyePosition() 返回 tick 末值，
            // 光插值朝向不插值眼位，玩家移动时框仍然会比画面慢半拍（各版本都实现了 (F) 重载）。
            Object interpolated = bridge.callMapped(player, ClassType.ENTITY, "getEyePosition",
                    Float.valueOf(partialTicks));
            if (bridge.isInstance(interpolated, ClassType.VEC3)) {
                eye = interpolated;
            }
        }
        if (eye == null) {
            Object raw = bridge.callMapped(player, ClassType.ENTITY, "getEyePosition");
            if (bridge.isInstance(raw, ClassType.VEC3)) {
                // 1.8.9 / 1.12.2 只有带参形态；这里命中说明零参确实存在（1.17+）。
                eye = raw;
            }
        }
        if (eye == null) {
            // 1.8.9–1.16.5 的签名带 partialTicks
            // （SRG func_174824_e(F) / MCP getPositionEyes(float)）。两形态各试一次——只试零参会让
            // A/B 代覆盖层整帧不画。
            Object fallback = bridge.callMapped(player, ClassType.ENTITY, "getEyePosition",
                    Float.valueOf(partialTicks));
            if (bridge.isInstance(fallback, ClassType.VEC3)) {
                eye = fallback;
            }
        }
        if (!bridge.isInstance(eye, ClassType.VEC3)) {
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
        // 朝向同样要插值：游戏用 yRotO/xRotO 与当帧 yRot/xRot 之间按 partialTicks 插值渲染。
        Double yawOld = number(bridge.readField(player, ClassType.ENTITY, "yRotO"));
        Double pitchOld = number(bridge.readField(player, ClassType.ENTITY, "xRotO"));
        if (INTERPOLATE && yawOld != null && pitchOld != null && partialTicks > 0f) {
            // yaw 必须先绕回 [-180,180) 再做差：直接减会在跨越 ±180 的那一帧得到 ∓358 度的差值，
            // 插值结果把相机瞬移到反方向——真机表现就是「一开渲染框，画面里的人/框在飞速乱窜」。
            // 游戏自己用的是 Mth.rotLerp，语义一致。
            yaw = Double.valueOf(yawOld.doubleValue() + wrapDegrees(yaw.doubleValue()
                    - yawOld.doubleValue()) * partialTicks);
            pitch = Double.valueOf(pitchOld.doubleValue()
                    + (pitch.doubleValue() - pitchOld.doubleValue()) * partialTicks);
        }
        Object options = minecraft == null
                ? null
                : bridge.readField(minecraft, ClassType.MINECRAFT, "options");
        Double fov = options == null ? null
                : fovOf(bridge, bridge.readField(options, ClassType.OPTIONS, "fov"));
        if (fov == null) {
            return missing(bridge, "Options#fov / OptionInstance#get");
        }
        projection.update(eyeX, eyeY, eyeZ, yaw.floatValue(), pitch.floatValue(), fov.floatValue(),
                width, height);
        return true;
    }

    /** @return 当帧投影（由 {@link #update} 刷新） */
    public WorldProjection projection() {
        return projection;
    }

    /**
     * 把角度差折回 {@code [-180, 180)}（等价于游戏里的 {@code Mth.wrapDegrees}）。
     *
     * @param degrees 原始角度差
     * @return 折算后的角度差
     */
    static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0d;
        if (wrapped >= 180.0d) {
            wrapped -= 360.0d;
        }
        if (wrapped < -180.0d) {
            wrapped += 360.0d;
        }
        return wrapped;
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
    static Double fovOf(GameBridge bridge, Object raw) {
        Double direct = number(raw);
        if (direct != null || raw == null) {
            return direct;
        }
        // Fabric/Forge 的运行期方法名不是 get，必须由映射表翻译。
        return number(bridge.callMapped(raw, ClassType.OPTION_INSTANCE, "get"));
    }

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
