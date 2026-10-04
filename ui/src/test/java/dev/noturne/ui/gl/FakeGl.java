package dev.noturne.ui.gl;

/**
 * {@code org.lwjgl.opengl.GL11} 的替身类：只记录立即模式调用次数与最后一次参数，不做任何真实绘制，
 * 使 {@link GlApi} 与 {@link GlRenderer} 可在无 GL 上下文的单元测试中运行。
 */
public final class FakeGl {

    /** {@code glColor4f} 调用次数 */
    public static int colorCalls;
    /** {@code glBegin} 调用次数 */
    public static int beginCalls;
    /** {@code glEnd} 调用次数 */
    public static int endCalls;
    /** {@code glVertex2f} 调用次数 */
    public static int vertexCalls;
    /** {@code glLineWidth} 调用次数 */
    public static int lineWidthCalls;
    /** 最后一次 {@code glBegin} 的图元模式；重置后为 -1 表示尚未绘制 */
    public static int lastBeginMode = -1;
    /** 最后一次 {@code glLineWidth} 的线宽；重置后为 -1 表示尚未设置 */
    public static float lastLineWidth = -1f;
    /** {@code glMatrixMode} 调用次数 */
    public static int matrixModeCalls;
    /** 最后一次 {@code glMatrixMode} 的目标；重置后为 -1 */
    public static int lastMatrixMode = -1;
    /** {@code glLoadIdentity} 调用次数 */
    public static int loadIdentityCalls;
    /** {@code glOrtho} 调用次数 */
    public static int orthoCalls;
    /** 最后一次 {@code glOrtho} 的边界参数（left, right, bottom, top, near, far）；未调用为 null */
    public static double[] lastOrtho;
    /** {@code glGetIntegerv} 调用次数 */
    public static int getIntegervCalls;
    /** {@code glScissor} 调用次数 */
    public static int scissorCalls;
    /** 最后一次 {@code glScissor} 的参数（x, y, width, height）；未调用为 null */
    public static int[] lastScissor;
    /** 模拟的视口（x, y, width, height），默认 1920×1080；测试可改写以模拟其他分辨率 */
    public static int[] viewport = {0, 0, 1920, 1080};

    /** 工具类，禁止实例化 */
    private FakeGl() {
    }

    /** 清空全部静态计数与参数记录；测试须在每个用例前调用以隔离断言范围 */
    public static void reset() {
        colorCalls = 0;
        beginCalls = 0;
        endCalls = 0;
        vertexCalls = 0;
        lineWidthCalls = 0;
        lastBeginMode = -1;
        lastLineWidth = -1f;
        matrixModeCalls = 0;
        lastMatrixMode = -1;
        loadIdentityCalls = 0;
        orthoCalls = 0;
        lastOrtho = null;
        getIntegervCalls = 0;
        scissorCalls = 0;
        lastScissor = null;
        viewport = new int[]{0, 0, 1920, 1080};
    }

    /** 记录一次颜色设置 */
    public static void glColor4f(float r, float g, float b, float a) {
        colorCalls++;
    }

    /**
     * 记录一次图元开始。
     *
     * @param mode 图元模式，取值应为 {@link GlApi} 中的 GL 常量
     */
    public static void glBegin(int mode) {
        beginCalls++;
        lastBeginMode = mode;
    }

    /** 记录一次图元结束 */
    public static void glEnd() {
        endCalls++;
    }

    /** 记录一个顶点 */
    public static void glVertex2f(float x, float y) {
        vertexCalls++;
    }

    /**
     * 记录一次线宽设置。
     *
     * @param width 线宽
     */
    public static void glLineWidth(float width) {
        lineWidthCalls++;
        lastLineWidth = width;
    }

    // ---- 矩阵、视口与裁剪：GlRenderer 用它们接管坐标系 ----

    /** 记录一次矩阵栈切换 */
    public static void glMatrixMode(int mode) {
        matrixModeCalls++;
        lastMatrixMode = mode;
    }

    /** 记录一次矩阵重置 */
    public static void glLoadIdentity() {
        loadIdentityCalls++;
    }

    /**
     * 记录一次正交投影设置。
     *
     * @param left,right,bottom,top,near,far 视景体边界，按 OpenGL 的调用顺序保存
     */
    public static void glOrtho(double left, double right, double bottom, double top,
                               double near, double far) {
        orthoCalls++;
        lastOrtho = new double[]{left, right, bottom, top, near, far};
    }

    /**
     * 查询视口，把 {@link #viewport} 拷进 {@code out}。
     *
     * <p>替身不做真实 GL 查询：测试通过改写 {@code viewport} 来模拟不同分辨率。
     */
    public static void glGetIntegerv(int target, int[] out) {
        getIntegervCalls++;
        if (out != null && out.length >= 4) {
            System.arraycopy(viewport, 0, out, 0, 4);
        }
    }

    /** 记录一次裁剪矩形设置 */
    public static void glScissor(int x, int y, int width, int height) {
        scissorCalls++;
        lastScissor = new int[]{x, y, width, height};
    }

    // 以下方法只需"存在"即可：GlApi 通过反射按签名查找它们，空实现满足绑定校验
    /** 启用某个 GL 能力；无操作 */
    public static void glEnable(int cap) {
    }

    /** 禁用某个 GL 能力；无操作 */
    public static void glDisable(int cap) {
    }

    /** 设置混合函数；无操作 */
    public static void glBlendFunc(int src, int dst) {
    }

    /** 压入矩阵栈；无操作 */
    public static void glPushMatrix() {
    }

    /** 弹出矩阵栈；无操作 */
    public static void glPopMatrix() {
    }

    /** 平移；无操作，调用方已自行完成坐标变换 */
    public static void glTranslatef(float x, float y, float z) {
    }

    /** 缩放；无操作 */
    public static void glScalef(float x, float y, float z) {
    }

    /** 设置纹理坐标；无操作 */
    public static void glTexCoord2f(float u, float v) {
    }

    /** 绑定纹理；无操作 */
    public static void glBindTexture(int target, int texture) {
    }
}
