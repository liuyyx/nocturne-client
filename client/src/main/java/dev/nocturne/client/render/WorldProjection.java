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

        this.rightX = cosYaw;
        this.rightY = 0d;
        this.rightZ = sinYaw;

        // up = right × look（右手系；pitch=0 时退化为 (0,1,0)）。
        this.upX = sinYaw * sinPitch;
        this.upY = cosPitch;
        this.upZ = -cosYaw * sinPitch;

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
        if (forward <= NEAR_PLANE) {
            return;   // 在相机背后或贴脸：投影会翻到另一侧，必须挡掉
        }
        double side = dx * rightX + dy * rightY + dz * rightZ;
        double above = dx * upX + dy * upY + dz * upZ;

        double ndcX = (side / forward) * focal / aspect;
        double ndcY = (above / forward) * focal;

        out.x = (float) ((ndcX + 1d) * 0.5d * viewportWidth);
        out.y = (float) ((1d - ndcY) * 0.5d * viewportHeight);
        // 投影落到视口 2 倍范围之外的点，画出来也只是屏幕外的废物（每个框要好几次绘制调用）。
        // 边距取宽松的 2 倍，保证"一半在屏幕内"的框仍然保留——只剔掉完全在远处的。
        if (out.x < -viewportWidth || out.x > viewportWidth * 2f
                || out.y < -viewportHeight || out.y > viewportHeight * 2f) {
            return;
        }
        out.visible = true;
        out.depth = forward;
    }
}
