package dev.nocturne.ui.gl;

import dev.nocturne.client.render3d.World3DBackend;
import dev.nocturne.client.render3d.World3DBatch;
import dev.nocturne.client.render3d.WorldCamera;

/**
 * 1.8.9 / LWJGL2 固定管线的世界空间 3D 后端：立即模式 + 复用游戏当帧矩阵。
 *
 * <p><b>为什么复用游戏矩阵</b>：自造 {@code glFrustum}/{@code glRotatef} 只能近似相机，且远平面
 * 与游戏不一致时深度值映射不同——超过 20 格的图元会与地形比较出错误结果（穿墙/被裁掉）。
 * 进入时直接读 {@code GL_PROJECTION_MATRIX} / {@code GL_MODELVIEW_MATRIX}：
 * <ul>
 *   <li>投影原样保留（push 后不改）；远平面、FOV、hurt 变色全部跟随游戏；</li>
 *   <li>模型视图只保留旋转（把平移列清零），从原矩阵解出相机世界原点 {@code C}，
 *       图元用 {@code (world - C)}(double 减、再转 float) 提交——F5/第三人称、viewBobbing、
 *       hurt 的旋转全部由游戏矩阵带来，同时避免 30M 坐标处 float 精度塌陷。</li>
 * </ul>
 * 只有矩阵读取失败时才退回自建透视/朝向；远平面取 {@code EntityRenderer#farPlaneDistance}
 * （1.8.9/1.12.2），未知时 512，绝不写死 1024。回退路径会打一次限制日志。
 *
 * <p><b>状态隔离</b>：{@code glPushAttrib(GL_ALL_ATTRIB_BITS)} + {@code glPushClientAttrib} +
 * 投影/模型视图矩阵栈。所有状态修改走 {@code *Direct}（绕开 {@code GlStateManager} 缓存），
 * 退出时栈把真实状态还原，缓存也仍停在进入前的值，不会串色/丢纹理。
 * 进入前显式清理纹理单元 0/1 的 {@code GL_TEXTURE_2D}、{@code GL_ALPHA_TEST}、{@code GL_FOG}。
 */
public final class GlWorld3DBackend implements World3DBackend {

    /** {@code glBegin} 的图元模式（OpenGL ABI 常量，永不改变）。 */
    private static final int GL_LINES = 1;
    private static final int GL_LINE_LOOP = 2;
    private static final int GL_TRIANGLE_STRIP = 5;

    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_ALPHA_TEST = 0x0BC0;
    private static final int GL_FOG = 0x0B60;
    private static final int GL_LIGHTING = 0x0B50;
    private static final int GL_DEPTH_TEST = 0x0B71;
    private static final int GL_CULL_FACE = 0x0B44;
    private static final int GL_BLEND = 0x0BE2;
    private static final int GL_SCISSOR_TEST = 0x0C11;
    private static final int GL_SRC_ALPHA = 770;
    private static final int GL_ONE_MINUS_SRC_ALPHA = 771;
    /** {@code GL_CLIENT_ALL_ATTRIB_BITS}：客户态属性全部。 */
    private static final int GL_CLIENT_ALL_ATTRIB_BITS = -1;

    /** 回退透视的近平面（与游戏一致）与未知远平面时的保守值。 */
    private static final double NEAR_PLANE = 0.05d;
    private static final double FALLBACK_FAR_PLANE = 512.0d;

    /** 模型视图解出的相机原点与 CameraState 眼位的最大允许偏差（第三人称拉远 ≤4 格）。 */
    private static final double MAX_CAMERA_DELTA = 64.0d;

    private final GlApi gl;
    private final GlBatch batch = new GlBatch();
    private final boolean available;
    private final String unavailableReason;

    /** 复用缓冲：矩阵查询/旋转矩阵/视口。 */
    private final double[] projectionMatrix = new double[16];
    private final double[] modelViewMatrix = new double[16];
    private final double[] rotationOnly = new double[16];
    /** 世界渲染头部捕获的矩阵（尾部绘制优先使用）。 */
    private final double[] capturedProjection = new double[16];
    private final double[] capturedModelView = new double[16];
    private boolean captured;
    private final int[] viewport = new int[4];

    private boolean entered;
    private int viewportWidth;
    private int viewportHeight;
    /** 本帧图元的相机相对原点（世界坐标）。 */
    private double originX;
    private double originY;
    private double originZ;
    /** 矩阵/回退限制日志是否已打印。 */
    private boolean matrixLogged;
    private boolean deriveLogged;
    private boolean fallbackLogged;
    private boolean reentryLogged;

    public GlWorld3DBackend(GlApi gl) {
        this.gl = gl;
        boolean ok = gl != null && gl.hasProjection3D();
        this.available = ok;
        this.unavailableReason = ok ? null : (gl == null
                ? "GL11 binding missing"
                : "GL11 binding lacks fixed-pipeline matrix/vertex entry points;"
                + " core profile cannot draw immediate mode");
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

    /**
     * 世界渲染头部：读一次投影/模型视图。此时模型视图还是世界相机（未被手部渲染改写）。
     * 失败时保持 {@code captured=false}，尾部会退回实时查询/相机近似。
     */
    @Override
    public void captureMatrices() {
        if (!available) {
            return;
        }
        boolean projection = gl.readMatrix(GlApi.GL_PROJECTION_MATRIX, capturedProjection);
        boolean model = gl.readMatrix(GlApi.GL_MODELVIEW_MATRIX, capturedModelView);
        captured = projection && model;
    }

    @Override
    public boolean begin(WorldCamera camera) {
        if (!available || camera == null || !camera.valid()) {
            return false;
        }
        if (entered) {
            if (!reentryLogged) {
                reentryLogged = true;
                log("world3d begin re-entered without end; refusing the second pass");
            }
            return false;
        }
        if (!gl.viewport(viewport)) {
            return false;
        }
        viewportWidth = viewport[2];
        viewportHeight = viewport[3];

        boolean capture = captured;
        captured = false;
        boolean projectionReuse;
        boolean modelReuse;
        if (capture) {
            System.arraycopy(capturedProjection, 0, projectionMatrix, 0, 16);
            System.arraycopy(capturedModelView, 0, modelViewMatrix, 0, 16);
            projectionReuse = isPerspective(projectionMatrix);
            modelReuse = deriveCameraOrigin(modelViewMatrix, camera);
        } else {
            projectionReuse = gl.readMatrix(GlApi.GL_PROJECTION_MATRIX, projectionMatrix)
                    && isPerspective(projectionMatrix);
            modelReuse = gl.readMatrix(GlApi.GL_MODELVIEW_MATRIX, modelViewMatrix)
                    && deriveCameraOrigin(modelViewMatrix, camera);
        }
        if (!projectionReuse && !gl.hasFallbackProjection()) {
            logOnceMatrix("world3d: game projection unreadable and no glFrustum fallback");
            return false;
        }
        if (!modelReuse && !gl.hasFallbackProjection()) {
            logOnceMatrix("world3d: game modelview unreadable and no glRotatef fallback");
            return false;
        }

        gl.pushAttrib(GlApi.GL_ALL_ATTRIB_BITS);
        boolean clientAttrib = gl.hasClientAttribStack();
        if (clientAttrib) {
            gl.pushClientAttrib(GL_CLIENT_ALL_ATTRIB_BITS);
        }
        gl.disableClientArrays();

        // 纹理单元 0/1 都关：1.8.9 的光照贴图在 1 号单元且游戏常不还原。
        gl.activeTexture(GlApi.GL_TEXTURE0);
        gl.disableDirect(GL_TEXTURE_2D);
        gl.activeTexture(GlApi.GL_TEXTURE0 + 1);
        gl.disableDirect(GL_TEXTURE_2D);
        gl.activeTexture(GlApi.GL_TEXTURE0);
        gl.disableDirect(GL_ALPHA_TEST);
        gl.disableDirect(GL_FOG);
        gl.disableDirect(GL_LIGHTING);
        gl.disableDirect(GL_SCISSOR_TEST);
        gl.disableDirect(GL_CULL_FACE);
        gl.disableDirect(GL_BLEND);
        gl.enableDirect(GL_DEPTH_TEST);
        gl.depthMask(true);

        gl.matrixMode(GlApi.GL_PROJECTION);
        gl.pushMatrix();
        if (!projectionReuse) {
            gl.loadIdentity();
            double aspect = (double) viewportWidth / (double) viewportHeight;
            float fov = camera.fov();
            if (!(fov > 1.0f && fov < 179.0f)) {
                fov = 70.0f; // 相机异常时不要拿 0/负值去算透视（会得到退化的视锥）
            }
            double far = camera.farPlane() > NEAR_PLANE
                    ? camera.farPlane() : FALLBACK_FAR_PLANE;
            double top = NEAR_PLANE * Math.tan(Math.toRadians(fov * 0.5d));
            double right = top * aspect;
            gl.frustum(-right, right, -top, top, NEAR_PLANE, far);
            logFallback("projection unreadable: rebuilt from camera FOV with far=" + far);
        }

        gl.matrixMode(GlApi.GL_MODELVIEW);
        gl.pushMatrix();
        if (modelReuse) {
            // 平移列清零：图元自己提交 (world - origin)，旋转（含 bobbing/F5/hurt）来自游戏。
            System.arraycopy(modelViewMatrix, 0, rotationOnly, 0, 16);
            rotationOnly[12] = 0.0d;
            rotationOnly[13] = 0.0d;
            rotationOnly[14] = 0.0d;
            if (!gl.loadMatrix(rotationOnly)) {
                logOnceMatrix("world3d: glLoadMatrixd failed; skipping frame");
                gl.matrixMode(GlApi.GL_MODELVIEW);
                gl.popMatrix();
                gl.matrixMode(GlApi.GL_PROJECTION);
                gl.popMatrix();
                gl.matrixMode(GlApi.GL_MODELVIEW);
                if (clientAttrib) {
                    gl.popClientAttrib();
                }
                gl.popAttrib();
                return false;
            }
        } else {
            gl.loadIdentity();
            gl.rotate(camera.pitch(), 1f, 0f, 0f);
            gl.rotate(camera.yaw() + 180f, 0f, 1f, 0f);
            originX = camera.eyeX();
            originY = camera.eyeY();
            originZ = camera.eyeZ();
            logFallback("modelview unreadable: rebuilt from camera eye/rotation"
                    + " (no viewBobbing / third-person offset)");
        }

        batch.reset();
        entered = true;
        if (System.getProperty("nocturne.debug.world3d") != null && !matrixLogged) {
            matrixLogged = true;
            double dx = originX - camera.eyeX();
            double dy = originY - camera.eyeY();
            double dz = originZ - camera.eyeZ();
            log("world3d matrices: source=" + (capture ? "pass" : "current")
                    + " projectionReuse=" + projectionReuse
                    + " modelReuse=" + modelReuse
                    + " origin=(" + round(originX) + "," + round(originY) + ","
                    + round(originZ) + ")"
                    + " eyeDelta=(" + round(dx) + "," + round(dy) + "," + round(dz) + ")"
                    + " far=" + (camera.farPlane() > 0f ? camera.farPlane() : "unknown"));
        }
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
            // 活动纹理单元也不属于属性栈，显式回到 0 号。
            gl.activeTexture(GlApi.GL_TEXTURE0);
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

    // ------------------------------------------------------------------ 矩阵解析

    /** @return 是否是标准 OpenGL 透视投影（底行 0,0,-1,0） */
    static boolean isPerspective(double[] m) {
        if (m == null || m.length < 16) {
            return false;
        }
        for (int i = 0; i < 16; i++) {
            if (Double.isNaN(m[i]) || Double.isInfinite(m[i])) {
                return false;
            }
        }
        return Math.abs(m[15]) < 1e-9d && m[11] < -0.5d && m[0] > 0.0d && m[5] > 0.0d;
    }

    /**
     * 从世界→视图矩阵解出相机世界原点，并校验它确实是世界空间模型视图。
     *
     * <p>MV = R · T(-C)（R 正交，含 bobbing/hurt/F5 旋转，T 平移），所以
     * {@code C = -Rᵀ · t}。用解出的 C 与 {@link WorldCamera} 眼位比较：偏差超过
     * {@link #MAX_CAMERA_DELTA} 说明当前矩阵不是世界相机（例如 HUD/手部），走回退路径。
     *
     * @return 是否可用；可用时写入 {@link #originX/Y/Z}
     */
    private boolean deriveCameraOrigin(double[] m, WorldCamera camera) {
        if (m == null || m.length < 16) {
            return false;
        }
        for (int i = 0; i < 16; i++) {
            if (Double.isNaN(m[i]) || Double.isInfinite(m[i])) {
                return false;
            }
        }
        // 游戏矩阵由 float 运算组成，回读成 double 后底行不会精确是 (0,0,0,1)；1e-4 足够。
        if (Math.abs(m[15] - 1.0d) > 1e-4d
                || Math.abs(m[3]) > 1e-4d || Math.abs(m[7]) > 1e-4d
                || Math.abs(m[11]) > 1e-4d) {
            logDeriveFailure("affine bottom row", m, 0, 0, 0);
            return false;
        }
        double r00 = m[0];
        double r01 = m[4];
        double r02 = m[8];
        double r10 = m[1];
        double r11 = m[5];
        double r12 = m[9];
        double r20 = m[2];
        double r21 = m[6];
        double r22 = m[10];
        // 列长度应约等于 1（纯旋转/反射）；缩放过的矩阵不适用本推导。
        if (Math.abs(length(r00, r10, r20) - 1.0d) > 0.1d
                || Math.abs(length(r01, r11, r21) - 1.0d) > 0.1d
                || Math.abs(length(r02, r12, r22) - 1.0d) > 0.1d) {
            logDeriveFailure("non-unit rotation column", m, 0, 0, 0);
            return false;
        }
        double tx = m[12];
        double ty = m[13];
        double tz = m[14];
        double cx = -(r00 * tx + r10 * ty + r20 * tz);
        double cy = -(r01 * tx + r11 * ty + r21 * tz);
        double cz = -(r02 * tx + r12 * ty + r22 * tz);
        if (Double.isNaN(cx) || Double.isNaN(cy) || Double.isNaN(cz)) {
            return false;
        }
        double dx = cx - camera.eyeX();
        double dy = cy - camera.eyeY();
        double dz = cz - camera.eyeZ();
        if (dx * dx + dy * dy + dz * dz > MAX_CAMERA_DELTA * MAX_CAMERA_DELTA) {
            logDeriveFailure("camera origin too far from eye", m, cx, cy, cz);
            return false;
        }
        originX = cx;
        originY = cy;
        originZ = cz;
        return true;
    }

    private static double length(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** 矩阵推导失败的一次性诊断（仅 -Dnocturne.debug.world3d 下打印）。 */
    private void logDeriveFailure(String reason, double[] m, double cx, double cy, double cz) {
        if (System.getProperty("nocturne.debug.world3d") == null || deriveLogged) {
            return;
        }
        deriveLogged = true;
        log("world3d modelview rejected: " + reason
                + " translation=(" + round(m[12]) + "," + round(m[13]) + "," + round(m[14]) + ")"
                + " derived=(" + round(cx) + "," + round(cy) + "," + round(cz) + ")"
                + " m15=" + m[15] + " colLen=(" + round(length(m[0], m[1], m[2])) + ","
                + round(length(m[4], m[5], m[6])) + "," + round(length(m[8], m[9], m[10])) + ")");
    }

    private void logOnceMatrix(String message) {
        if (!matrixLogged) {
            matrixLogged = true;
            log(message);
        }
    }

    private void logFallback(String message) {
        if (!fallbackLogged) {
            fallbackLogged = true;
            log("world3d fallback: " + message);
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private static void log(String message) {
        System.out.println("[nocturne] " + message);
    }

    /**
     * 立即模式批次：逐图元 {@code glBegin/glEnd}，状态只在值变化时重设。
     *
     * <p>盒子/圆环各自合并成一个 GL 批次（12 条边 = 24 个顶点；圆环 = 一个
     * {@code GL_LINE_LOOP}）。顶点在 double 里减去相机原点后再转 float，大坐标不丢精度。
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

        /** 世界坐标 → 相机相对坐标（double 减，最后一步才转 float）。 */
        private void vertex(double x, double y, double z) {
            gl.vertex3((float) (x - originX), (float) (y - originY), (float) (z - originZ));
        }

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2) {
            if (!entered) {
                return;
            }
            applyState();
            gl.begin(GL_LINES);
            submitColor();
            vertex(x1, y1, z1);
            vertex(x2, y2, z2);
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
            vertex(x1, y1, z1);
            vertex(x2, y2, z2);
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
                vertex(centerX + Math.cos(angle) * radius, centerY,
                        centerZ + Math.sin(angle) * radius);
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
                vertex(xyz[base], xyz[base + 1], xyz[base + 2]);
            }
            gl.end();
        }

        @Override
        public void flush() {
            // 立即模式没有待提交的缓冲；保留给未来的 VBO 实现。
        }
    }
}
