package dev.nocturne.ui.gl;

import dev.nocturne.client.render3d.World3DBackend;
import dev.nocturne.client.render3d.World3DBatch;
import dev.nocturne.client.render3d.WorldCamera;

/**
 * 1.8.9 / LWJGL2 固定管线的世界空间 3D 后端：立即模式 + 相机透视。
 *
 * <p>只依赖通过游戏类加载器反射绑定的 {@link GlApi}，编译期不链接 LWJGL——这与
 * {@link GlRenderer} 走同一条约束。
 *
 * <p><b>状态还原</b>：进入时压入 {@code glPushAttrib(GL_ALL_ATTRIB_BITS)} 与
 * {@code glPushClientAttrib}，退出时弹出；矩阵分别压入投影栈与模型视图栈。所有状态修改都走
 * {@code *Direct} 入口，绕开游戏的 {@code GlStateManager} 缓存——属性栈会把真实状态还原成进入前
 * 的值，缓存本来也还停在那个值，于是缓存与真实状态在退出后仍一致。若走缓存写入，缓存会记住我们在
 * 绘制期间设的颜色/能力位，游戏之后「同色/同状态」的请求会被它跳过，表现为文字串色、几何消失。
 *
 * <p><b>相机变换</b>：投影用有效 FOV + 帧缓冲宽高算透视；模型视图先用游戏同款顺序
 * {@code rotate(pitch, X) → rotate(yaw + 180, Y) → translate(-eye)}，因此图元坐标就是绝对世界坐标。
 * 这样不依赖钩子点处游戏留下的矩阵，换一个世界绘制尾点也不会画歪。
 */
public final class GlWorld3DBackend implements World3DBackend {

    /** {@code glBegin} 的图元模式（OpenGL ABI 常量，永不改变）。 */
    private static final int GL_LINES = 1;
    private static final int GL_LINE_LOOP = 2;
    private static final int GL_TRIANGLE_STRIP = 5;

    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_LIGHTING = 0x0B50;
    private static final int GL_DEPTH_TEST = 0x0B71;
    private static final int GL_CULL_FACE = 0x0B44;
    private static final int GL_BLEND = 0x0BE2;
    private static final int GL_SCISSOR_TEST = 0x0C11;
    private static final int GL_SRC_ALPHA = 770;
    private static final int GL_ONE_MINUS_SRC_ALPHA = 771;
    /** {@code GL_CLIENT_ALL_ATTRIB_BITS}：客户态属性全部。 */
    private static final int GL_CLIENT_ALL_ATTRIB_BITS = -1;

    /** 近/远裁剪面：只影响深度精度，不影响屏幕位置。 */
    private static final double NEAR_PLANE = 0.05d;
    private static final double FAR_PLANE = 1024.0d;

    private final GlApi gl;
    private final GlBatch batch = new GlBatch();
    /** 是否具备完整固定管线入口。 */
    private final boolean available;
    /** 不可用原因。 */
    private final String unavailableReason;

    private boolean entered;
    private int viewportWidth;
    private int viewportHeight;

    public GlWorld3DBackend(GlApi gl) {
        this.gl = gl;
        boolean ok = gl != null && gl.hasProjection3D();
        this.available = ok;
        this.unavailableReason = ok ? null : (gl == null
                ? "GL11 binding missing"
                : "GL11 binding lacks fixed-pipeline entry points (glVertex3f/glRotatef/glFrustum/"
                + "attribute stack); core profile cannot draw immediate mode");
    }

    @Override
    public String backendName() {
        return "gl-fixed";
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String unavailableReason() {
        return unavailableReason;
    }

    @Override
    public boolean begin(WorldCamera camera) {
        if (!available || camera == null || !camera.valid()) {
            return false;
        }
        int[] viewport = gl.viewport();
        if (viewport == null || viewport[2] <= 0 || viewport[3] <= 0) {
            return false;
        }
        viewportWidth = viewport[2];
        viewportHeight = viewport[3];

        // 属性栈覆盖能力位/混合/线宽/颜色/深度掩码；客户态栈覆盖顶点/颜色/纹理坐标数组。
        gl.pushAttrib(GlApi.GL_ALL_ATTRIB_BITS);
        boolean clientAttrib = gl.hasClientAttribStack();
        if (clientAttrib) {
            gl.pushClientAttrib(GL_CLIENT_ALL_ATTRIB_BITS);
        }
        gl.disableClientArrays();

        // 基线状态：无纹理、无光照、无裁剪；深度开、剔光/混合关、深度可写。
        gl.disableDirect(GL_TEXTURE_2D);
        gl.disableDirect(GL_LIGHTING);
        gl.disableDirect(GL_SCISSOR_TEST);
        gl.disableDirect(GL_CULL_FACE);
        gl.disableDirect(GL_BLEND);
        gl.enableDirect(GL_DEPTH_TEST);
        gl.depthMask(true);

        double aspect = (double) viewportWidth / (double) viewportHeight;
        float fov = camera.fov();
        if (!(fov > 1.0f && fov < 179.0f)) {
            fov = 70.0f; // 相机异常时不要拿 0/负值去算透视（会得到退化的视锥）
        }
        double top = NEAR_PLANE * Math.tan(Math.toRadians(fov * 0.5d));
        double right = top * aspect;

        gl.matrixMode(GlApi.GL_PROJECTION);
        gl.pushMatrix();
        gl.loadIdentity();
        gl.frustum(-right, right, -top, top, NEAR_PLANE, FAR_PLANE);

        gl.matrixMode(GlApi.GL_MODELVIEW);
        gl.pushMatrix();
        gl.loadIdentity();
        // 与游戏 setupCameraTransform 同序：先俯仰、再偏航（+180，游戏 yaw 0=朝南）、最后平移。
        gl.rotate(camera.pitch(), 1f, 0f, 0f);
        gl.rotate(camera.yaw() + 180f, 0f, 1f, 0f);
        gl.translateD(-camera.eyeX(), -camera.eyeY(), -camera.eyeZ());

        batch.reset();
        entered = true;
        return true;
    }

    @Override
    public void end() {
        if (!entered) {
            return;
        }
        entered = false;
        try {
            batch.flush();
            gl.matrixMode(GlApi.GL_MODELVIEW);
            gl.popMatrix();
            gl.matrixMode(GlApi.GL_PROJECTION);
            gl.popMatrix();
            // 矩阵模式不属于属性栈；还原成游戏最常用的模型视图模式。
            gl.matrixMode(GlApi.GL_MODELVIEW);
            if (gl.hasClientAttribStack()) {
                gl.popClientAttrib();
            }
            gl.popAttrib();
        } finally {
            viewportWidth = 0;
            viewportHeight = 0;
        }
    }

    @Override
    public World3DBatch batch() {
        return entered ? batch : null;
    }

    @Override
    public int viewportWidth() {
        return viewportWidth;
    }

    @Override
    public int viewportHeight() {
        return viewportHeight;
    }

    /**
     * 立即模式批次：逐图元 {@code glBegin/glEnd}，状态只在值变化时重设。
     *
     * <p>盒子/圆环各自合并成一个 GL 批次（12 条边 = 24 个顶点；圆环 = 一个
     * {@code GL_LINE_LOOP}），不会退化成每边一次 {@code glBegin}。
     */
    private final class GlBatch implements World3DBatch {

        /** 未初始化的「已应用值」哨兵，强制第一次设置时真的下发。 */
        private static final int UNSET = -1;
        private static final float WIDTH_UNSET = -1f;

        private int color = 0xFFFFFFFF;
        private float width = 1.5f;
        private boolean depth = true;
        private boolean cull;
        private boolean blend;

        private int appliedDepth = UNSET;
        private int appliedCull = UNSET;
        private int appliedBlendFlag = UNSET;
        private float appliedWidth = WIDTH_UNSET;

        void reset() {
            color = 0xFFFFFFFF;
            width = 1.5f;
            depth = true;
            cull = false;
            blend = false;
            appliedDepth = UNSET;
            appliedCull = UNSET;
            appliedBlendFlag = UNSET;
            appliedWidth = WIDTH_UNSET;
        }

        @Override
        public World3DBatch color(int argb) {
            color = argb;
            return this;
        }

        @Override
        public World3DBatch lineWidth(float value) {
            width = value;
            return this;
        }

        @Override
        public World3DBatch depthTest(boolean enabled) {
            depth = enabled;
            return this;
        }

        @Override
        public World3DBatch cull(boolean enabled) {
            cull = enabled;
            return this;
        }

        @Override
        public World3DBatch blend(boolean enabled) {
            blend = enabled;
            return this;
        }

        /** 在 {@code glBegin} 之前把有变化的状态同步到 GL。 */
        private void applyState() {
            int wantDepth = depth ? 1 : 0;
            if (wantDepth != appliedDepth) {
                if (depth) {
                    gl.enableDirect(GL_DEPTH_TEST);
                } else {
                    gl.disableDirect(GL_DEPTH_TEST);
                }
                appliedDepth = wantDepth;
            }
            int wantCull = cull ? 1 : 0;
            if (wantCull != appliedCull) {
                if (cull) {
                    gl.enableDirect(GL_CULL_FACE);
                } else {
                    gl.disableDirect(GL_CULL_FACE);
                }
                appliedCull = wantCull;
            }
            int wantBlend = blend ? 1 : 0;
            if (wantBlend != appliedBlendFlag) {
                if (blend) {
                    gl.enableDirect(GL_BLEND);
                    gl.blendFuncDirect(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                } else {
                    gl.disableDirect(GL_BLEND);
                }
                appliedBlendFlag = wantBlend;
            }
            if (width != appliedWidth) {
                gl.lineWidth(width);
                appliedWidth = width;
            }
        }

        private void submitColor() {
            gl.colorDirect(((color >>> 16) & 0xFF) / 255.0f,
                    ((color >>> 8) & 0xFF) / 255.0f,
                    (color & 0xFF) / 255.0f,
                    ((color >>> 24) & 0xFF) / 255.0f);
        }

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2) {
            if (!entered) {
                return;
            }
            applyState();
            gl.begin(GL_LINES);
            submitColor();
            gl.vertex3((float) x1, (float) y1, (float) z1);
            gl.vertex3((float) x2, (float) y2, (float) z2);
            gl.end();
        }

        @Override
        public void box(double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ) {
            if (!entered) {
                return;
            }
            applyState();
            gl.begin(GL_LINES);
            submitColor();
            // 12 条棱：先沿 X，再沿 Y，最后沿 Z。
            edge(minX, minY, minZ, maxX, minY, minZ);
            edge(minX, minY, maxZ, maxX, minY, maxZ);
            edge(minX, maxY, minZ, maxX, maxY, minZ);
            edge(minX, maxY, maxZ, maxX, maxY, maxZ);
            edge(minX, minY, minZ, minX, maxY, minZ);
            edge(minX, minY, maxZ, minX, maxY, maxZ);
            edge(maxX, minY, minZ, maxX, maxY, minZ);
            edge(maxX, minY, maxZ, maxX, maxY, maxZ);
            edge(minX, minY, minZ, minX, minY, maxZ);
            edge(minX, maxY, minZ, minX, maxY, maxZ);
            edge(maxX, minY, minZ, maxX, minY, maxZ);
            edge(maxX, maxY, minZ, maxX, maxY, maxZ);
            gl.end();
        }

        /** 在已打开的 {@code glBegin} 里提交一条边（调用方负责 end）。 */
        private void edge(double x1, double y1, double z1, double x2, double y2, double z2) {
            gl.vertex3((float) x1, (float) y1, (float) z1);
            gl.vertex3((float) x2, (float) y2, (float) z2);
        }

        @Override
        public void ring(double centerX, double centerY, double centerZ, double radius,
                         int segments) {
            if (!entered) {
                return;
            }
            int count = Math.max(3, segments);
            applyState();
            gl.begin(GL_LINE_LOOP);
            submitColor();
            for (int i = 0; i < count; i++) {
                double angle = (Math.PI * 2.0d) * i / count;
                gl.vertex3((float) (centerX + Math.cos(angle) * radius),
                        (float) centerY,
                        (float) (centerZ + Math.sin(angle) * radius));
            }
            gl.end();
        }

        @Override
        public void triangleStrip(float[] xyz, int vertexCount) {
            if (!entered || xyz == null) {
                return;
            }
            int available = xyz.length / 3;
            int count = Math.min(Math.max(0, vertexCount), available);
            if (count < 3) {
                return;
            }
            applyState();
            gl.begin(GL_TRIANGLE_STRIP);
            submitColor();
            for (int i = 0; i < count; i++) {
                int base = i * 3;
                gl.vertex3(xyz[base], xyz[base + 1], xyz[base + 2]);
            }
            gl.end();
        }

        @Override
        public void flush() {
            // 立即模式没有待提交的缓冲；保留给未来的 VBO 实现。
        }
    }
}
