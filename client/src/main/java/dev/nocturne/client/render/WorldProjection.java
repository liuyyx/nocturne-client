package dev.nocturne.client.render;

/**
 * 世界 → 屏幕投影：自己按 FOV、朝向与视口算，**不用**游戏的投影矩阵。
 *
 * <p>为什么自己算：三代游戏拿投影矩阵的方式完全不同（1.8.9 的 {@code EntityRenderer}、
 * 1.16.5 的 {@code GameRenderer.getProjectionMatrix}、26.x 的提取阶段各一套），而且矩阵要按
 * 当前 {@code PoseStack} 变换。自己算只需要三样东西——相机眼位、yaw/pitch、FOV——它们在每个版本都
 * 能从映射表里拿到同一组成员（{@code Entity#getEyePosition} / {@code #yRot} / {@code #xRot} /
 * {@code Options#fov}），于是同一份代码在三代上都能画。
 *
 * <p><b>坐标约定（跟游戏一致）</b>：
 * <ul>
 *   <li>{@code yaw}：0 = 朝南（+Z），增大即向西转（90 = 朝西 / -X，180 = 朝北 / -Z）——
 *       也就是从上方看是逆时针；</li>
 *   <li>{@code pitch}：正 = 向下看；</li>
 *   <li>视口是**逻辑坐标**（游戏 GUI 缩放后的尺寸），与叠加层后端的 {@code width()/height()} 一致。</li>
 * </ul>
 *
 * <p><b>输出</b>：屏幕像素坐标 + 是否可见（在相机背后或过近时不可见）。不做深度遮挡剔除——
 * 叠加层画在游戏画面之上，本来就盖住一切；需要遮挡的场合由调用方自己判。
 *
 * <p>线程安全：每帧在渲染线程上 {@link #update} 一次、随后读取，不做同步（与调用方同一线程）。
 */
public final class WorldProjection {

    /** 相机眼位。 */
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    /** 视线方向（单位向量，由 yaw/pitch 推出）。 */
    private double lookX;
    private double lookY;
    private double lookZ;
    /** 相机右向量（单位向量，水平）。 */
    private double rightX;
    private double rightY;
    private double rightZ;
    /** 相机上向量（单位向量）。 */
    private double upX;
    private double upY;
    private double upZ;
    /** 垂直 FOV 的一半的正切倒数（{@code 1 / tan(fov/2)}）。 */
    private double focal;
    /** 视口宽高（逻辑像素）。 */
    private int viewportWidth;
    private int viewportHeight;
    /** 视口宽高比；为 0 表示尚未更新。 */
    private double aspect;

    /** 近平面：比这更近的点算不可见（在相机背后或贴着镜头）。 */
    private static final double NEAR_PLANE = 0.05;

    /** 一个世界点投影到屏幕后的结果。 */
    public static final class ScreenPoint {
        /** 屏幕 x（像素）。 */
        public float x;
        /** 屏幕 y（像素）。 */
        public float y;
        /** 是否可见：在相机前方且视口已就绪。 */
        public boolean visible;
        /** 相机空间的前向深度（米）；不可见时为 0。 */
        public double depth;
    }

    /**
     * 用当前相机状态刷新投影参数；每帧绘制前调用一次。
     *
     * @param eyeX          相机眼位 x（世界坐标）
     * @param eyeY          相机眼位 y
     * @param eyeZ          相机眼位 z
     * @param yawDegrees    实体 yaw（度；0 = 朝南）
     * @param pitchDegrees  实体 pitch（度；正 = 向下）
     * @param fovDegrees    垂直 FOV（度）；≤1 或 ≥179 时夹到安全区间
     * @param width         视口宽（逻辑像素）
     * @param height        视口高（逻辑像素）
     */
    public void update(double eyeX, double eyeY, double eyeZ, float yawDegrees, float pitchDegrees,
                       float fovDegrees, int width, int height) {
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.viewportWidth = width;
        this.viewportHeight = height;
        this.aspect = height <= 0 ? 0d : (double) width / height;

        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);

        // 与游戏的视角约定一致：yaw=0 朝 +Z，pitch 正为向下。
        this.lookX = -sinYaw * cosPitch;
        this.lookY = -sinPitch;
        this.lookZ = cosYaw * cosPitch;

        // 屏幕右方向：yaw=0 面朝 +Z（南）时，玩家的右手边是 -X（西）——所以是 -cosYaw/-sinYaw，
        // 不是 +。写反的后果是全部世界覆盖物水平镜像（真实症状：玩家看到的框画在视线另一侧）。
        this.rightX = -cosYaw;
        this.rightY = 0d;
        this.rightZ = -sinYaw;

        // up = right × look（右手系；pitch=0 时退化为 (0,1,0)）。
        // X/Z 的符号必须与上面的 right 配套：写反会让 up 在 pitch≠0 时与视线反向
        // （up·look 变成 -1），视场里所有点被投到同一条水平线上——"世界覆盖物塌成一条线"。
        this.upX = -sinYaw * sinPitch;
        this.upY = cosPitch;
        this.upZ = cosYaw * sinPitch;

        double fov = fovDegrees;
        if (fov < 1f) {
            fov = 1f;
        } else if (fov > 179f) {
            fov = 179f;
        }
        this.focal = 1d / Math.tan(Math.toRadians(fov) / 2d);
    }

    /** @return 视口是否已就绪（宽高与宽高比都有效） */
    public boolean isReady() {
        return viewportWidth > 0 && viewportHeight > 0 && aspect > 0d;
    }

    /** @return 视口宽（逻辑像素） */
    public int viewportWidth() {
        return viewportWidth;
    }

    /** @return 视口高（逻辑像素） */
    public int viewportHeight() {
        return viewportHeight;
    }

    /** @return 相机右向量的 x 分量；供 billboard/光环在 CPU 侧算四角。 */
    public double rightX() {
        return rightX;
    }

    /** @return 相机右向量的 y 分量 */
    public double rightY() {
        return rightY;
    }

    /** @return 相机右向量的 z 分量 */
    public double rightZ() {
        return rightZ;
    }

    /** @return 相机上向量的 x 分量 */
    public double upX() {
        return upX;
    }

    /** @return 相机上向量的 y 分量 */
    public double upY() {
        return upY;
    }

    /** @return 相机上向量的 z 分量 */
    public double upZ() {
        return upZ;
    }

    /** @return 相机视线向量的 x 分量 */
    public double lookX() {
        return lookX;
    }

    /** @return 相机视线向量的 y 分量 */
    public double lookY() {
        return lookY;
    }

    /** @return 相机视线向量的 z 分量 */
    public double lookZ() {
        return lookZ;
    }

    /**
     * 把一个世界点投影到屏幕。
     *
     * @param worldX 世界坐标 x
     * @param worldY 世界坐标 y
     * @param worldZ 世界坐标 z
     * @return 投影结果（复用同一个对象会更快；本实现每次新建，调用方自行复用可减少分配）
     */
    public ScreenPoint project(double worldX, double worldY, double worldZ) {
        ScreenPoint point = new ScreenPoint();
        project(worldX, worldY, worldZ, point);
        return point;
    }

    /**
     * 把一个世界点投影到屏幕，结果写进调用方提供的对象（热路径上用这个避免每帧分配）。
     *
     * @param worldX 世界坐标 x
     * @param worldY 世界坐标 y
     * @param worldZ 世界坐标 z
     * @param out    输出对象
     */
    /** 把屏幕坐标夹到视口内（近平面插值/超大包围盒后必须夹，否则框被撑到屏幕外）。 */
    static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    public void project(double worldX, double worldY, double worldZ, ScreenPoint out) {
        out.x = 0f;
        out.y = 0f;
        out.visible = false;
        out.depth = 0d;
        if (!isReady()) {
            return;
        }
        double dx = worldX - eyeX;
        double dy = worldY - eyeY;
        double dz = worldZ - eyeZ;

        double forward = dx * lookX + dy * lookY + dz * lookZ;
        if (forward <= 0d) {
            return;   // 在相机背后：投影会翻到另一侧，必须挡掉
        }
        double side = dx * rightX + dy * rightY + dz * rightZ;
        double above = dx * upX + dy * upY + dz * upZ;

        // 近平面裁剪：forward 落在 (0, NEAR] 时**不能**整点丢弃——贴箱子的 8 个角里只要一个
        // 近点，整个框就会消失或跳到屏幕另一侧（真机表现：贴住箱子时框涨满屏）。
        // 把该点沿视线插值到近平面再做透视除法，等价于"截断后仍然落在正确方向上"。
        double depth = Math.max(forward, NEAR_PLANE);

        double ndcX = (side / depth) * focal / aspect;
        double ndcY = (above / depth) * focal;

        float rawX = (float) ((ndcX + 1d) * 0.5d * viewportWidth);
        float rawY = (float) ((1d - ndcY) * 0.5d * viewportHeight);
        // depth 无条件写出：调用方（AABB 8 角包围盒）需要用它判断"在相机前方"，
        // 而 visible 只表示"落在视口 2 倍范围内"，两者的语义不能混。
        out.depth = forward;
        // 投影落到视口 2 倍范围之外的点，画出来也只是屏幕外的废物（每个框要好几次绘制调用）。
        // 边距取宽松的 2 倍，保证"一半在屏幕内"的框仍然保留——只剔掉完全在远处的。
        if (rawX < -viewportWidth || rawX > viewportWidth * 2f
                || rawY < -viewportHeight || rawY > viewportHeight * 2f) {
            return;
        }
        // 屏幕矩形必须 clamp 到视口：8 角包围盒取 min/max 时，越界角的原始屏幕坐标会把
        // 整个框撑到屏幕外（尤其近平面插值后 x/y 会变得很大）。
        out.x = clamp(rawX, 0f, viewportWidth);
        out.y = clamp(rawY, 0f, viewportHeight);
        out.visible = true;
    }
}
