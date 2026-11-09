package dev.nocturne.client.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.render3d.WorldCamera;

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
    /** 最近一次读到的相机快照（复用实例）；世界空间 3D 通道直接读取它。 */
    private final dev.nocturne.client.render3d.WorldCamera view =
            new dev.nocturne.client.render3d.WorldCamera();

    /** 是否已经就缺成员打过一次诊断（避免每帧刷屏）。 */
    private boolean missingLogged;

    /** 相机输入探针是否已打印（{@code -Dnocturne.debug.camera}）。 */
    private boolean cameraProbeLogged;
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
        return update(bridge, width, height, Float.NaN);
    }

    /**
     * 读取相机并刷新投影，允许调用方提供当帧 {@code partialTicks}。
     *
     * <p>世界空间 3D 通道在游戏世界绘制时拿得到精确的插值量（方法形参），不必依赖
     * {@code Minecraft#pausePartialTick} 之类的反射回退。
     *
     * @param bridge              游戏桥；为 {@code null} 或游戏未就绪时返回 {@code false}
     * @param width               视口宽（逻辑像素）
     * @param height              视口高（逻辑像素）
     * @param partialTicksOverride {@code NaN} 表示自行探测；否则直接使用该值
     * @return 是否成功刷新（{@code false} 表示本帧不该画覆盖物）
     */
    public boolean update(GameBridge bridge, int width, int height, float partialTicksOverride) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (!read(bridge, partialTicksOverride)) {
            return false;
        }
        WorldCamera camera = view;
        // 不再用 Aspect Ratio 模块的 ratio 改这里的 fov：模块并没有改游戏投影，覆盖层若自行
        // 改 fov，开关模块时框会整体移动（同一帧两种投影）。要真正改宽高比应该改游戏投影本身。
        projection.update(camera.eyeX(), camera.eyeY(), camera.eyeZ(), camera.yaw(),
                camera.pitch(), camera.fov(), width, height);
        if (System.getProperty("nocturne.debug.camera") != null && !cameraProbeLogged) {
            cameraProbeLogged = true;
            // 一次打印相机输入：投影算错时先看这里——yaw/pitch 合理范围、eye 与玩家位置一致、
            // fov 在 30–110，viewport 是 GUI 逻辑尺寸。
            System.out.println("[nocturne] camera: eye=(" + Math.round(camera.eyeX() * 100) / 100.0
                    + "," + Math.round(camera.eyeY() * 100) / 100.0 + ","
                    + Math.round(camera.eyeZ() * 100) / 100.0 + ")"
                    + " yaw=" + camera.yaw() + " pitch=" + camera.pitch()
                    + " pt=" + camera.partialTicks() + " fov=" + camera.fov()
                    + " viewport=" + width + "x" + height);
        }
        return true;
    }

    /**
     * 只读取相机快照（眼位 / 朝向 / 有效 FOV / partialTicks），不碰 2D 投影。
     *
     * <p>世界空间 3D 后端自己设置投影与模型视图矩阵，只要这份快照；视口用帧缓冲像素，
     * 由后端从 {@code GL_VIEWPORT} 读回。
     *
     * @param bridge              游戏桥；为 {@code null} 时返回 {@code false}
     * @param partialTicksOverride {@code NaN} 表示自行探测
     * @return 是否读到了完整相机
     */
    public boolean read(GameBridge bridge, float partialTicksOverride) {
        if (bridge == null) {
            view.clear();
            return false;
        }
        Object player = bridge.player();
        if (player == null) {
            view.clear();
            return false;
        }
        Object minecraft = bridge.minecraft();
        // 渲染插值量：游戏按 partialTicks 在「上一个 tick」与「当前 tick」之间插值渲染，覆盖层若直接用
        // tick 末值，60-120fps 的画面里框会以 20Hz 阶梯抖动（真机表现就是「框在甩、像加速」）。
        // 1.13–1.20 从 Minecraft.pausePartialTick 取；更老/更新的版本拿不到就退回 0（等同 tick 末值）。
        float partialTicks = Float.isNaN(partialTicksOverride)
                ? partialTicks(bridge, minecraft) : partialTicksOverride;
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
        // 相机位姿：F5 / Freelook 下相机不等于玩家眼位与朝向；能拿到 Camera 就用它，
        // 拿不到（1.8.9 没有 getMainCamera 映射）就沿用玩家眼位——绝不因为缺一项而整帧不画。
        Object renderer = minecraft == null ? null
                : bridge.readField(minecraft, ClassType.MINECRAFT, "gameRenderer");
        Object camera = renderer == null ? null
                : bridge.callMapped(renderer, ClassType.GAME_RENDERER, "getMainCamera");
        if (camera != null) {
            Object position = bridge.callMapped(camera, ClassType.CAMERA, "getPosition");
            Double cameraX = number(bridge.readField(position, ClassType.VEC3, "x"));
            Double cameraY = number(bridge.readField(position, ClassType.VEC3, "y"));
            Double cameraZ = number(bridge.readField(position, ClassType.VEC3, "z"));
            Double cameraYaw = number(bridge.callMapped(camera, ClassType.CAMERA, "getYRot"));
            Double cameraPitch = number(bridge.callMapped(camera, ClassType.CAMERA, "getXRot"));
            if (cameraX != null && cameraY != null && cameraZ != null
                    && cameraYaw != null && cameraPitch != null) {
                eyeX = cameraX;
                eyeY = cameraY;
                eyeZ = cameraZ;
                yaw = cameraYaw;
                pitch = cameraPitch;
            }
        }
        Object options = minecraft == null
                ? null
                : bridge.readField(minecraft, ClassType.MINECRAFT, "options");
        // 有效 FOV 优先：游戏会把 FOV 按疾跑/水下/药水修正后再投影，只读 options.fov 会让
        // 冲刺时的框整体偏移。拿不到有效 FOV（映射缺失）才退回静态设置值。
        Double fov = effectiveFov(bridge, renderer, camera, partialTicks);
        if (fov == null) {
            fov = options == null ? null
                    : fovOf(bridge, bridge.readField(options, ClassType.OPTIONS, "fov"));
        }
        if (fov == null) {
            return missing(bridge, "Options#fov / OptionInstance#get");
        }
        view.set(eyeX.doubleValue(), eyeY.doubleValue(), eyeZ.doubleValue(),
                yaw.floatValue(), pitch.floatValue(), fov.floatValue(), partialTicks);
        // 游戏真实远平面：只在固定管线世代存在（1.8.9/1.12.2 的 EntityRenderer#farPlaneDistance）。
        // 矩阵读取失败时用它与 0.05 近平面重建同口径透视，避免自造 1024 导致深度比较错位。
        if (renderer != null && !bridge.mapping().isMemberAbsent(
                ClassType.ENTITY_RENDERER, "farPlaneDistance")) {
            Double far = number(bridge.readField(renderer, ClassType.ENTITY_RENDERER,
                    "farPlaneDistance"));
            view.setFarPlane(far == null ? 0f : far.floatValue());
        } else {
            view.setFarPlane(0f);
        }
        return true;
    }

    /**
     * @return 最近一次 {@link #read} / {@link #update} 读到的相机快照（复用实例）
     */
    public dev.nocturne.client.render3d.WorldCamera view() {
        return view;
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
     * 当帧渲染插值量 {@code partialTicks}（0–1），拿不到时返回 0（等同 tick 末值）。
     *
     * <p>能力顺序（不写版本判断，只按成员是否存在降级）：
     * <ol>
     *   <li>{@code DeltaTracker#getGameTimeDeltaPartialTick(boolean)}（1.21+）；</li>
     *   <li>{@code DeltaTracker#partialTick} 字段（1.21 旧名）；</li>
     *   <li>{@code Minecraft#pausePartialTick}（1.13–1.20）。</li>
     * </ol>
     * 1.8.9 的 {@code Timer#renderPartialTicks} 需要 Timer 类映射（当前表里没有），因此该版本
     * 退回 0——帧回调 delta 由调用方（叠加层）自行决定是否使用。
     */
    private static float partialTicks(GameBridge bridge, Object minecraft) {
        if (minecraft == null) {
            return 0f;
        }
        Object tracker = bridge.readField(minecraft, ClassType.MINECRAFT, "deltaTracker");
        if (tracker == null) {
            tracker = bridge.readField(minecraft, ClassType.MINECRAFT, "timer");
        }
        if (tracker != null) {
            Double raw = number(bridge.callMapped(tracker, ClassType.DELTA_TRACKER,
                    "getGameTimeDeltaPartialTick", Boolean.TRUE));
            if (raw == null) {
                raw = number(bridge.readField(tracker, ClassType.DELTA_TRACKER, "partialTick"));
            }
            if (raw != null && raw.doubleValue() >= 0d && raw.doubleValue() <= 1d) {
                return raw.floatValue();
            }
        }
        Double legacy = number(bridge.readField(minecraft, ClassType.MINECRAFT, "pausePartialTick"));
        if (legacy != null && legacy.doubleValue() >= 0d && legacy.doubleValue() <= 1d) {
            return legacy.floatValue();
        }
        return 0f;
    }

    /**
     * 读视场角：1.8.9–1.16.4 的 {@code Options#fov} 是 {@code int}，1.16.5 起是
     * {@code OptionInstance<Integer>}。后者直接当数字读会拿到 {@code null}——覆盖层于是整帧不画，
     * 而日志只留一句"cannot read Options#fov"（26.3 实测就是这个）。
     *
     * @param raw {@code Options#fov} 的原始读数（可能是数字，也可能是 {@code OptionInstance}）
     * @return 视场角；两种形态都取不到时返回 {@code null}
     */
    /**
     * 有效 FOV：现代走 {@code GameRenderer#getFov(Camera, float, boolean)}；1.8.9–1.12
     * 没有独立 Camera，改走 {@code EntityRenderer#getFOVModifier(float, boolean)}。
     * 两者都拿不到时返回 {@code null}，由调用方退回 {@code options.fov} 静态值。
     *
     * <p>按映射能力选择（{@code isMemberAbsent}），不写版本判断。
     *
     * @param bridge        游戏桥
     * @param renderer      游戏渲染器（{@code Minecraft.gameRenderer}）
     * @param camera        26.x 的 Camera，旧版为 {@code null}
     * @param partialTicks  插值系数
     * @return 有效 FOV；不可用为 {@code null}
     */
    static Double effectiveFov(GameBridge bridge, Object renderer, Object camera,
                               float partialTicks) {
        Double fov = number(renderer == null || camera == null ? null
                : bridge.callMapped(renderer, ClassType.GAME_RENDERER, "getFov", camera,
                        Float.valueOf(partialTicks), Boolean.TRUE));
        if (fov == null && renderer != null
                && !bridge.mapping().isMemberAbsent(ClassType.ENTITY_RENDERER, "getFOVModifier")) {
            fov = number(bridge.callMapped(renderer, ClassType.ENTITY_RENDERER,
                    "getFOVModifier", Float.valueOf(partialTicks), Boolean.TRUE));
        }
        return fov;
    }

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
        view.clear();
        if (!missingLogged) {
            missingLogged = true;
            System.out.println("[nocturne] world overlay: cannot read " + member
                    + " on this version; overlay drawing is off (mapping=" + bridge.mapping().describe()
                    + ")");
        }
        return false;
    }
}
