package dev.nocturne.client.module.modules.render;

/**
 * 弹射物弹道模拟器（上游 {@code ProjectileSimulator} 的行为移植，纯数学、不触碰游戏类）。
 *
 * <p>按物品给出初速/重力/阻力/更新顺序常量表，逐步推进位置并做方块碰撞（体素 DDA）。
 * 实体命中与渲染由 {@link TrajectoriesModule} 在外层处理——模拟器只认
 * {@link WorldView} 提供的方块/水/边界查询，因此单测可以用假网格精确验证。
 *
 * <p>上游降级：穿透（Piercing）与多重射击（Multishot）依赖跨版本不可靠的附魔读取，
 * 恒按 0 处理（三叉戟/弩只画单条弹道），javadoc 与模块文档均已注明。
 */
final class ProjectileSimulator {

    /** 方块/水/世界边界查询面；由模块用 GameBridge 实现，测试用假网格实现。 */
    interface WorldView {
        /** 坐标所在格是否为实心碰撞方块。 */
        boolean solid(double x, double y, double z);

        /** 坐标所在格是否为水。 */
        boolean water(double x, double y, double z);

        /** 坐标所在区块是否已加载（未加载视为出界停止）。 */
        boolean loaded(double x, double y, double z);

        /** 世界最低高度（低于此停止）。 */
        double minY();
    }

    /** 一步推进结果；由模拟器复用，下一次 tick 会覆盖其内容。 */
    static final class Step {
        double x;
        double y;
        double z;
        /** 命中方块面：法向轴（0=x,1=y,2=z），未命中为 -1。 */
        int faceAxis;
        /** 命中面法向符号（±1），未命中为 0。 */
        int faceSign;
        boolean stop;

        Step set(double x, double y, double z, int faceAxis, int faceSign, boolean stop) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.faceAxis = faceAxis;
            this.faceSign = faceSign;
            this.stop = stop;
            return this;
        }
    }

    /** 推进顺序：不同弹射物的「重力/阻力/位移」结算次序不同，轨迹形状因此不同。 */
    private enum Order {
        GRAVITY_DRAG_POSITION,
        POSITION_DRAG_GRAVITY,
        GRAVITY_POSITION_DRAG
    }

    /** 运动参数表：初速倍率、俯仰偏移(roll)、重力、空气/水阻力、更新顺序与碰撞盒尺寸。 */
    static final class Data {
        final double power;
        final double roll;
        final double gravity;
        final double airDrag;
        final double waterDrag;
        final Order order;
        final double width;
        final double height;

        Data(double power, double roll, double gravity, double airDrag, double waterDrag,
             Order order, double width, double height) {
            this.power = power;
            this.roll = roll;
            this.gravity = gravity;
            this.airDrag = airDrag;
            this.waterDrag = waterDrag;
            this.order = order;
            this.width = width;
            this.height = height;
        }

        Data withPower(double newPower) {
            return new Data(newPower, roll, gravity, airDrag, waterDrag, order, width, height);
        }
    }

    private static Data throwable(double power, double roll, double gravity) {
        return new Data(power, roll, gravity, 0.99, 0.8, Order.GRAVITY_DRAG_POSITION, 0.25, 0.25);
    }

    static final Data EGG = throwable(1.5, 0.0, 0.03);
    static final Data ENDER_PEARL = throwable(1.5, 0.0, 0.03);
    static final Data SNOWBALL = throwable(1.5, 0.0, 0.03);
    static final Data EXPERIENCE_BOTTLE = throwable(0.7, -20.0, 0.07);
    static final Data LINGERING_POTION = throwable(0.5, -20.0, 0.05);
    static final Data SPLASH_POTION = throwable(0.5, -20.0, 0.05);
    static final Data WIND_CHARGE =
            new Data(1.5, 0.0, 0.0, 1.0, 1.0, Order.GRAVITY_DRAG_POSITION, 0.25, 0.25);
    static final Data EXPLOSIVE =
            new Data(0.0, 0.0, 0.0, 1.0, 1.0, Order.GRAVITY_DRAG_POSITION, 1.0, 1.0);
    static final Data ARROW =
            new Data(0.0, 0.0, 0.05, 0.99, 0.6, Order.POSITION_DRAG_GRAVITY, 0.5, 0.5);
    static final Data TRIDENT =
            new Data(2.5, 0.0, 0.05, 0.99, 0.99, Order.POSITION_DRAG_GRAVITY, 0.5, 0.5);
    static final Data LLAMA_SPIT =
            new Data(0.0, 0.0, 0.06, 0.99, 0.0, Order.POSITION_DRAG_GRAVITY, 0.25, 0.25);
    static final Data FIREWORK =
            new Data(0.0, 0.0, 0.0, 1.0, 1.0, Order.GRAVITY_POSITION_DRAG, 0.25, 0.25);
    static final Data FISHING_BOBBER =
            new Data(0.0, 0.0, 0.03, 0.92, 0.0, Order.GRAVITY_POSITION_DRAG, 0.25, 0.25);

    private double x;
    private double y;
    private double z;
    private double vx;
    private double vy;
    private double vz;
    private Data motion = ARROW;
    private boolean inWater;
    private int ticks;
    private boolean gravityDisabled;
    private final Step step = new Step();
    private final int[] face = new int[2];

    /**
     * 手持发射配置：从眼睛位置（调用方已把原点抬到眼高 -0.1）沿 yaw/pitch 方向以
     * {@code data.power} 初速射出；{@code angleOffset} 为绕视线正上方轴的附加偏角
     * （上游多重射击用；本仓库恒为 0）。
     *
     * @return false 表示参数不可用（调用方应跳过该玩家）
     */
    boolean configureShot(Data data, double originX, double originY, double originZ,
                          double yawDeg, double pitchDeg, double angleOffsetDeg,
                          double upX, double upY, double upZ, boolean noGravity) {
        return configureShot(data, data.power, originX, originY, originZ,
                yawDeg, pitchDeg, angleOffsetDeg, upX, upY, upZ, noGravity);
    }

    /** 手持弓与弩按蓄力传入初速，不修改或复制物品参数表。 */
    boolean configureShot(Data data, double power, double originX, double originY, double originZ,
                          double yawDeg, double pitchDeg, double angleOffsetDeg,
                          double upX, double upY, double upZ, boolean noGravity) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg + data.roll);
        double directionX = -Math.sin(yaw) * Math.cos(pitch);
        double directionY = -Math.sin(pitch);
        double directionZ = Math.cos(yaw) * Math.cos(pitch);

        if (angleOffsetDeg != 0.0) {
            double angle = Math.toRadians(angleOffsetDeg);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double crossX = upY * directionZ - upZ * directionY;
            double crossY = upZ * directionX - upX * directionZ;
            double crossZ = upX * directionY - upY * directionX;
            double dot = upX * directionX + upY * directionY + upZ * directionZ;
            directionX = directionX * cos + crossX * sin + upX * dot * (1.0 - cos);
            directionY = directionY * cos + crossY * sin + upY * dot * (1.0 - cos);
            directionZ = directionZ * cos + crossZ * sin + upZ * dot * (1.0 - cos);
        }

        double length = Math.sqrt(directionX * directionX + directionY * directionY
                + directionZ * directionZ);
        if (length < 1.0E-8 || power <= 0.0) {
            return false;
        }
        x = originX;
        y = originY;
        z = originZ;
        vx = directionX / length * power;
        vy = directionY / length * power;
        vz = directionZ / length * power;
        motion = data;
        gravityDisabled = noGravity;
        ticks = 0;
        inWater = false;
        return true;
    }

    /** 钓鱼竿浮漂配置（上游 configureFishing 的逐式移植，符号顺序保持一致）。 */
    void configureFishing(double originX, double originY, double originZ,
                          double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double yawCos = Math.cos(-yaw - Math.PI);
        double yawSin = Math.sin(-yaw - Math.PI);
        double pitchCos = -Math.cos(-pitch);
        double pitchSin = Math.sin(-pitch);

        x = originX - yawSin * 0.3;
        y = originY;
        z = originZ - yawCos * 0.3;
        double vxRaw = -yawSin;
        double vyRaw = -(pitchSin / pitchCos);
        if (vyRaw > 5.0) {
            vyRaw = 5.0;
        } else if (vyRaw < -5.0) {
            vyRaw = -5.0;
        }
        double vzRaw = -yawCos;
        double length = Math.sqrt(vxRaw * vxRaw + vyRaw * vyRaw + vzRaw * vzRaw);
        double scale = 0.6 / length + 0.5;
        vx = vxRaw * scale;
        vy = vyRaw * scale;
        vz = vzRaw * scale;
        motion = FISHING_BOBBER;
        gravityDisabled = false;
        ticks = 0;
        inWater = false;
    }

    /** 已发射弹射物配置：直接以当前位置/速度接续模拟。 */
    boolean configureFired(Data data, double px, double py, double pz,
                           double mvx, double mvy, double mvz,
                           boolean wasInWater, int existingTicks, boolean noGravity) {
        if (mvx * mvx + mvy * mvy + mvz * mvz <= 1.0E-8) {
            return false;
        }
        x = px;
        y = py;
        z = pz;
        vx = mvx;
        vy = mvy;
        vz = mvz;
        motion = data;
        gravityDisabled = noGravity;
        ticks = existingTicks;
        inWater = wasInWater;
        return true;
    }

    double x() {
        return x;
    }

    double y() {
        return y;
    }

    double z() {
        return z;
    }

    /**
     * 推进一个 tick：按运动表顺序结算重力/阻力/位移，随后做水状态更新与方块碰撞。
     * 命中时位置被钳到进入格子的边界点，{@link Step#faceAxis}/{@link Step#faceSign}
     * 给出命中面法向。
     */
    Step tick(WorldView world) {
        double previousX = x;
        double previousY = y;
        double previousZ = z;
        ticks++;

        if (!world.loaded(x, y, z)) {
            return step.set(x, y, z, -1, 0, true);
        }
        double drag = inWater ? motion.waterDrag : motion.airDrag;
        double gravity = gravityDisabled ? 0.0 : motion.gravity;
        switch (motion.order) {
            case GRAVITY_DRAG_POSITION:
                vy -= gravity;
                vx *= drag;
                vy *= drag;
                vz *= drag;
                x += vx;
                y += vy;
                z += vz;
                if (world.loaded(x, y, z)) updateWaterState(world);
                break;
            case POSITION_DRAG_GRAVITY:
                x += vx;
                y += vy;
                z += vz;
                vx *= drag;
                vy = vy * drag - gravity;
                vz *= drag;
                if (world.loaded(x, y, z)) updateWaterState(world);
                break;
            case GRAVITY_POSITION_DRAG:
                updateWaterState(world);
                vy -= gravity;
                x += vx;
                y += vy;
                z += vz;
                vx *= drag;
                vy *= drag;
                vz *= drag;
                break;
            default:
                break;
        }

        if (y < world.minY()) {
            return step.set(x, y, z, -1, 0, true);
        }

        if (walkBlocks(world, previousX, previousY, previousZ, x, y, z, face)) {
            return step.set(x, y, z, face[0], face[1], true);
        }
        return step.set(x, y, z, -1, 0, false);
    }

    /** 水状态：以弹射物碰撞盒（内缩 0.001）覆盖的格子判定任一水为水。 */
    private void updateWaterState(WorldView world) {
        double halfW = motion.width * 0.5 - 0.001;
        double halfH = motion.height * 0.5 - 0.001;
        int minX = (int) Math.floor(x - halfW);
        int maxX = (int) Math.floor(x + halfW);
        int minY = (int) Math.floor(y - halfH);
        int maxY = (int) Math.floor(y + halfH);
        int minZ = (int) Math.floor(z - halfW);
        int maxZ = (int) Math.floor(z + halfW);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    if (world.loaded(bx + 0.5, by + 0.5, bz + 0.5)
                            && world.water(bx + 0.5, by + 0.5, bz + 0.5)) {
                        inWater = true;
                        return;
                    }
                }
            }
        }
        inWater = false;
    }

    /**
     * 体素 DDA 沿 prev→cur 逐格检查加载状态及碰撞；
     * 碰撞或进入未加载区域时钳到边界，后者不报告命中面。
     *
     * @return true 表示撞上实心格或进入未加载区域
     */
    private boolean walkBlocks(WorldView world, double fromX, double fromY, double fromZ,
                               double toX, double toY, double toZ, int[] faceOut) {
        int cellX = (int) Math.floor(fromX);
        int cellY = (int) Math.floor(fromY);
        int cellZ = (int) Math.floor(fromZ);
        int targetX = (int) Math.floor(toX);
        int targetY = (int) Math.floor(toY);
        int targetZ = (int) Math.floor(toZ);

        if (world.solid(cellX + 0.5, cellY + 0.5, cellZ + 0.5)) {
            faceOut[0] = 1;
            faceOut[1] = 1;
            return true;
        }
        if (cellX == targetX && cellY == targetY && cellZ == targetZ) {
            return false;
        }

        double stepX = toX > fromX ? 1 : -1;
        double stepY = toY > fromY ? 1 : -1;
        double stepZ = toZ > fromZ ? 1 : -1;
        double tMaxX = intBound(fromX, toX - fromX);
        double tMaxY = intBound(fromY, toY - fromY);
        double tMaxZ = intBound(fromZ, toZ - fromZ);
        double tDeltaX = Math.abs(1.0 / (toX - fromX));
        double tDeltaY = Math.abs(1.0 / (toY - fromY));
        double tDeltaZ = Math.abs(1.0 / (toZ - fromZ));

        int guard = 0;
        int lastAxis = -1;
        while (!(cellX == targetX && cellY == targetY && cellZ == targetZ) && guard++ < 512) {
            int axis;
            int sign;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                cellX += stepX;
                tMaxX += tDeltaX;
                axis = 0;
                sign = (int) -stepX;
            } else if (tMaxY <= tMaxX && tMaxY < tMaxZ) {
                cellY += stepY;
                tMaxY += tDeltaY;
                axis = 1;
                sign = (int) -stepY;
            } else {
                cellZ += stepZ;
                tMaxZ += tDeltaZ;
                axis = 2;
                sign = (int) -stepZ;
            }
            lastAxis = axis;
            boolean loaded = world.loaded(cellX + 0.5, cellY + 0.5, cellZ + 0.5);
            if (!loaded || world.solid(cellX + 0.5, cellY + 0.5, cellZ + 0.5)) {
                faceOut[0] = loaded ? axis : -1;
                faceOut[1] = loaded ? sign : 0;
                clampToBoundary(fromX, fromY, fromZ, toX, toY, toZ,
                        cellX + (stepX > 0 ? 0 : 1),
                        cellY + (stepY > 0 ? 0 : 1),
                        cellZ + (stepZ > 0 ? 0 : 1), axis);
                return true;
            }
        }
        if (cellX != targetX || cellY != targetY || cellZ != targetZ) {
            // 过长的单步射线超出遍历上限时停在已检查边界，不穿过未知区域。
            faceOut[0] = -1;
            faceOut[1] = 0;
            clampToBoundary(fromX, fromY, fromZ, toX, toY, toZ,
                    cellX + (stepX > 0 ? 0 : 1),
                    cellY + (stepY > 0 ? 0 : 1),
                    cellZ + (stepZ > 0 ? 0 : 1), lastAxis);
            return true;
        }
        return false;
    }

    /** 命中后把位置钳到射线进入命中格子的边界面上（沿命中轴取格边界）。 */
    private void clampToBoundary(double fromX, double fromY, double fromZ,
                                 double toX, double toY, double toZ,
                                 int boundaryX, int boundaryY, int boundaryZ, int axis) {
        double denominator;
        double t;
        switch (axis) {
            case 0:
                denominator = toX - fromX;
                if (Math.abs(denominator) < 1.0E-8) {
                    return;
                }
                t = (boundaryX - fromX) / denominator;
                break;
            case 1:
                denominator = toY - fromY;
                if (Math.abs(denominator) < 1.0E-8) {
                    return;
                }
                t = (boundaryY - fromY) / denominator;
                break;
            default:
                denominator = toZ - fromZ;
                if (Math.abs(denominator) < 1.0E-8) {
                    return;
                }
                t = (boundaryZ - fromZ) / denominator;
                break;
        }
        if (t < 0.0 || t > 1.0) {
            return;
        }
        x = fromX + (toX - fromX) * t;
        y = fromY + (toY - fromY) * t;
        z = fromZ + (toZ - fromZ) * t;
    }

    /** DDA 的「射线参数 u∈[0,1] 到下一个整数边界的距离」（u 空间已归一）。 */
    private static double intBound(double s, double delta) {
        if (Math.abs(delta) < 1.0E-8) {
            return Double.MAX_VALUE;
        }
        double distance = delta > 0 ? Math.floor(s + 1.0) - s : s - Math.floor(s);
        return distance / Math.abs(delta);
    }

    /** 弓的蓄力公式：{@code ((t/20)^2 + 2·(t/20)) / 4}，封顶 1（vanilla getPowerForTime）。 */
    static float bowPower(int useTicks) {
        float f = useTicks / 20.0F;
        f = (f * f + f * 2.0F) / 4.0F;
        return f > 1.0F ? 1.0F : f;
    }
}
