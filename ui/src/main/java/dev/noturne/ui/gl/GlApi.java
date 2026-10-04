package dev.noturne.ui.gl;

import dev.noturne.client.game.Reflect;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * 对 LWJGL 固定管线 {@code GL11} 的反射绑定。
 *
 * <p>编译期无法链接 LWJGL（注入器运行在操作者的 JVM 上，而非游戏进程内），
 * 因此每个调用都通过针对游戏类加载器解析出的方法句柄转发。
 *
 * <p>只绑定 UI 所需的立即模式（immediate mode）子集；GL 枚举以内联常量给出——
 * 它们属于 OpenGL ABI，永不改变。
 *
 * <p>在架构中的位置：{@link GlRenderer} 依赖本类完成 1.8.9 / LWJGL2 时代的绘制；
 * 它与 {@link ModernGlApi} 并列，构成跨越两代管线的底层 GL 抽象，使 UI 层无需在编译期依赖 LWJGL。
 */
public final class GlApi {

    /** glBegin 图元模式：独立线段。 */
    public static final int GL_LINES = 1;
    /** glBegin 图元模式：首尾相连的闭合线段环。 */
    public static final int GL_LINE_LOOP = 2;
    /** glBegin 图元模式：三角扇，用于圆角等扇形几何。 */
    public static final int GL_TRIANGLE_FAN = 6;
    /** glBegin 图元模式：四边形（固定管线专有，核心 profile 已移除）。 */
    public static final int GL_QUADS = 7;
    /** glEnable/glDisable 的混合（alpha blending）开关。 */
    public static final int GL_BLEND = 3042;
    /** 混合因子：源颜色。 */
    public static final int GL_SRC_ALPHA = 770;
    /** 混合因子：1 - 源 alpha。 */
    public static final int GL_ONE_MINUS_SRC_ALPHA = 771;
    /** 线段抗锯齿开关。 */
    public static final int GL_LINE_SMOOTH = 2848;
    /** 2D 纹理目标 / 纹理开关。 */
    public static final int GL_TEXTURE_2D = 3553;
    /** 背面剔除开关。 */
    public static final int GL_CULL_FACE = 2884;
    /** {@code glMatrixMode} 的目标：投影矩阵。 */
    public static final int GL_PROJECTION = 5889;
    /** {@code glMatrixMode} 的目标：模型视图矩阵。 */
    public static final int GL_MODELVIEW = 5888;
    /** 深度测试开关；绘制 UI 时必须关闭，否则后画的元素会被先画的遮挡。 */
    public static final int GL_DEPTH_TEST = 2929;
    /** {@code glGetIntegerv} 的查询目标：当前视口，结果为 {@code {x, y, width, height}}。 */
    public static final int GL_VIEWPORT = 2978;
    /** 裁剪测试开关，配合 {@code glScissor} 实现矩形裁剪。 */
    public static final int GL_SCISSOR_TEST = 3089;

    /** 已解析的 {@code glColor4f}，设置当前顶点颜色。 */
    private final Method color4f;
    /** 已解析的 {@code glBegin}，开始一个立即模式图元批次。 */
    private final Method begin;
    /** 已解析的 {@code glEnd}，结束当前批次。 */
    private final Method end;
    /** 已解析的 {@code glVertex2f}，提交一个二维顶点。 */
    private final Method vertex2f;
    /** 已解析的 {@code glEnable}，开启某项 GL 能力。 */
    private final Method enable;
    /** 已解析的 {@code glDisable}，关闭某项 GL 能力。 */
    private final Method disable;
    /** 已解析的 {@code glBlendFunc}，设置混合因子。 */
    private final Method blendFunc;
    /** 已解析的 {@code glPushMatrix}，压入当前变换矩阵栈。 */
    private final Method pushMatrix;
    /** 已解析的 {@code glPopMatrix}，弹出变换矩阵栈。 */
    private final Method popMatrix;
    /** 已解析的 {@code glTranslatef}，平移当前矩阵。 */
    private final Method translatef;
    /** 已解析的 {@code glScalef}，缩放当前矩阵。 */
    private final Method scalef;
    /** 已解析的 {@code glLineWidth}，设置线段宽度。 */
    private final Method lineWidth;
    /** 已解析的 {@code glTexCoord2f}，提交二维纹理坐标。 */
    private final Method texCoord2f;
    /** 已解析的 {@code glBindTexture}，绑定纹理对象。 */
    private final Method bindTexture;
    /** 已解析的 {@code glMatrixMode}，切换当前操作的矩阵栈。 */
    private final Method matrixMode;
    /** 已解析的 {@code glLoadIdentity}，把当前矩阵重置为单位矩阵。 */
    private final Method loadIdentity;
    /** 已解析的 {@code glOrtho}，建立正交投影。 */
    private final Method ortho;
    /** 已解析的 {@code glGetIntegerv}，查询 GL 状态（LWJGL3 形态：{@code (int, int[])}）。 */
    private final Method getIntegerv;
    /**
     * 已解析的 {@code glGetInteger(int, IntBuffer)}，查询 GL 状态（LWJGL2 形态）。
     *
     * <p>LWJGL2 的 GL11 <b>没有</b> {@code glGetIntegerv(int, int[])}：只接受 {@code IntBuffer}。
     * 若只绑定数组形态，1.8.9 上视口永远读不出来（全 0），{@link GlRenderer#beginFrame()} 会据此
     * 建立 {@code glOrtho(0,0,0,0)}——该调用在 GL 里是非法的（GL_INVALID_VALUE，矩阵不变），
     * GUI 会永久停在裁剪空间之外而完全不可见。
     */
    private final Method getIntegervBuffer;
    /**
     * {@link #getIntegervBuffer} 用的直接缓冲：GL 要求直接缓冲 + 本地字节序，复用避免每帧分配。
     *
     * <p>容量必须是 <b>16 个 int</b> 而不是恰好 4 个：LWJGL2 的 {@code glGetInteger(int, IntBuffer)}
     * 会用 {@code BufferChecks.checkBuffer(params, 16)} 校验余量，容量不足直接抛
     * {@code IllegalArgumentException}（被 {@link Reflect#call} 吞掉后表现为「视口永远是 0x0」）。
     */
    private final IntBuffer viewportBuffer;
    /** 已解析的 {@code glScissor}，设置裁剪矩形。 */
    private final Method scissor;

    /**
     * 保存已解析的方法句柄。
     *
     * <p>仅供 {@link #bind(Class)} 内部调用；句柄可能为 {@code null}，具体包装方法据此决定是否发起调用。
     */
    private GlApi(Method color4f, Method begin, Method end, Method vertex2f, Method enable,
                  Method disable, Method blendFunc, Method pushMatrix, Method popMatrix,
                  Method translatef, Method scalef, Method lineWidth, Method texCoord2f,
                  Method bindTexture, Method matrixMode, Method loadIdentity, Method ortho,
                  Method getIntegerv, Method getIntegervBuffer, Method scissor) {
        this.color4f = color4f;
        this.begin = begin;
        this.end = end;
        this.vertex2f = vertex2f;
        this.enable = enable;
        this.disable = disable;
        this.blendFunc = blendFunc;
        this.pushMatrix = pushMatrix;
        this.popMatrix = popMatrix;
        this.translatef = translatef;
        this.scalef = scalef;
        this.lineWidth = lineWidth;
        this.texCoord2f = texCoord2f;
        this.bindTexture = bindTexture;
        this.matrixMode = matrixMode;
        this.loadIdentity = loadIdentity;
        this.ortho = ortho;
        this.getIntegerv = getIntegerv;
        this.getIntegervBuffer = getIntegervBuffer;
        this.viewportBuffer = ByteBuffer.allocateDirect(16 * Integer.BYTES)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        this.scissor = scissor;
    }

    /**
     * 通过 {@code loader} 加载 {@code org.lwjgl.opengl.GL11} 并绑定；加载不到时返回 {@code null}。
     *
     * @param className GL 类名（通常为 {@code "org.lwjgl.opengl.GL11"}）
     * @param loader    游戏类加载器，用于解析 LWJGL 类
     */
    public static GlApi bind(String className, ClassLoader loader) {
        return bind(Reflect.load(className, loader));
    }

    /**
     * 绑定一个已解析的 GL 类（测试也可用替身类调用）。
     *
     * @return 可用的绑定；若缺少绘制所需的核心句柄（color/begin/end/vertex）则返回 {@code null}
     */
    public static GlApi bind(Class<?> gl) {
        if (gl == null) {
            return null;
        }
        Method color4f = Reflect.method(gl, "glColor4f", float.class, float.class, float.class, float.class);
        Method begin = Reflect.method(gl, "glBegin", int.class);
        Method end = Reflect.method(gl, "glEnd");
        Method vertex2f = Reflect.method(gl, "glVertex2f", float.class, float.class);
        if (color4f == null || begin == null || end == null || vertex2f == null) {
            return null; // 核心句柄不全，无法作为可用的 GL11
        }
        return new GlApi(
                color4f, begin, end, vertex2f,
                Reflect.method(gl, "glEnable", int.class),
                Reflect.method(gl, "glDisable", int.class),
                Reflect.method(gl, "glBlendFunc", int.class, int.class),
                Reflect.method(gl, "glPushMatrix"),
                Reflect.method(gl, "glPopMatrix"),
                Reflect.method(gl, "glTranslatef", float.class, float.class, float.class),
                Reflect.method(gl, "glScalef", float.class, float.class, float.class),
                Reflect.method(gl, "glLineWidth", float.class),
                Reflect.method(gl, "glTexCoord2f", float.class, float.class),
                Reflect.method(gl, "glBindTexture", int.class, int.class),
                Reflect.method(gl, "glMatrixMode", int.class),
                Reflect.method(gl, "glLoadIdentity"),
                Reflect.method(gl, "glOrtho", double.class, double.class, double.class,
                        double.class, double.class, double.class),
                Reflect.method(gl, "glGetIntegerv", int.class, int[].class),
                // LWJGL2 只有 glGetInteger(int, IntBuffer)；LWJGL3 两个形态都有。先试 LWJGL2 的名字，
                // 再退到 LWJGL3 的 glGetIntegerv(int, IntBuffer) 重载。
                orElse(Reflect.method(gl, "glGetInteger", int.class, IntBuffer.class),
                        Reflect.method(gl, "glGetIntegerv", int.class, IntBuffer.class)),
                Reflect.method(gl, "glScissor", int.class, int.class, int.class, int.class));
    }

    /**
     * 设置当前顶点颜色（对应 {@code glColor4f}）。
     *
     * @param r,g,b,a 分量，取值 0–1
     */
    public void color(float r, float g, float b, float a) {
        Reflect.call(color4f, null, r, g, b, a);
    }

    /**
     * 开始一个立即模式图元批次（对应 {@code glBegin}）。
     *
     * @param mode 图元模式，取本类的 {@code GL_*} 常量之一
     */
    public void begin(int mode) {
        Reflect.call(begin, null, mode);
    }

    /** 结束当前立即模式批次（对应 {@code glEnd}）。 */
    public void end() {
        Reflect.call(end, null);
    }

    /** 提交一个二维顶点（对应 {@code glVertex2f}），坐标以窗口像素为单位。 */
    public void vertex(float x, float y) {
        Reflect.call(vertex2f, null, x, y);
    }

    /** 开启某项 GL 能力（对应 {@code glEnable}）。 */
    public void enable(int cap) {
        Reflect.call(enable, null, cap);
    }

    /** 关闭某项 GL 能力（对应 {@code glDisable}）。 */
    public void disable(int cap) {
        Reflect.call(disable, null, cap);
    }

    /** 设置 alpha 混合的源/目标因子（对应 {@code glBlendFunc}）。 */
    public void blendFunc(int src, int dst) {
        Reflect.call(blendFunc, null, src, dst);
    }

    /** 压入当前模型视图矩阵（对应 {@code glPushMatrix}）。 */
    public void pushMatrix() {
        Reflect.call(pushMatrix, null);
    }

    /** 弹出模型视图矩阵栈（对应 {@code glPopMatrix}），必须与 {@link #pushMatrix()} 成对使用。 */
    public void popMatrix() {
        Reflect.call(popMatrix, null);
    }

    /** 平移当前矩阵（对应 {@code glTranslatef}）；UI 通常只在 z 传 0。 */
    public void translate(float x, float y, float z) {
        Reflect.call(translatef, null, x, y, z);
    }

    /** 缩放当前矩阵（对应 {@code glScalef}）。 */
    public void scale(float x, float y, float z) {
        Reflect.call(scalef, null, x, y, z);
    }

    /** 设置线段宽度（对应 {@code glLineWidth}）。 */
    public void lineWidth(float width) {
        Reflect.call(lineWidth, null, width);
    }

    /** 提交二维纹理坐标（对应 {@code glTexCoord2f}）。 */
    public void texCoord(float u, float v) {
        Reflect.call(texCoord2f, null, u, v);
    }

    /** 绑定纹理对象（对应 {@code glBindTexture}）。 */
    public void bindTexture(int target, int texture) {
        Reflect.call(bindTexture, null, target, texture);
    }

    /** 切换当前操作的矩阵栈（对应 {@code glMatrixMode}），参数取 {@link #GL_PROJECTION} 或 {@link #GL_MODELVIEW}。 */
    public void matrixMode(int mode) {
        Reflect.call(matrixMode, null, mode);
    }

    /** 把当前矩阵重置为单位矩阵（对应 {@code glLoadIdentity}）。 */
    public void loadIdentity() {
        Reflect.call(loadIdentity, null);
    }

    /**
     * 建立正交投影（对应 {@code glOrtho}）。
     *
     * @param left,right,bottom,top 视景体的左右下上边界（世界坐标）
     * @param near,far              近远裁剪面距离；UI 用 {@code -1, 1} 即可，因为所有顶点都在 z=0
     */
    public void ortho(double left, double right, double bottom, double top, double near, double far) {
        Reflect.call(ortho, null, left, right, bottom, top, near, far);
    }

    /**
     * 查询当前视口。
     *
     * @return {@code {x, y, width, height}}；句柄缺失时返回 {@code null}，查询失败时各项为 0
     */
    public int[] viewport() {
        int[] out = new int[4];
        if (getIntegerv != null) {
            // LWJGL3 形态：直接写进 int[]。
            Reflect.call(getIntegerv, null, GL_VIEWPORT, out);
            if (out[2] > 0 && out[3] > 0) {
                return out;
            }
        }
        if (getIntegervBuffer != null) {
            // LWJGL2 形态：写进直接缓冲后再取回四个分量（GL 调用不会移动缓冲区位置）。
            viewportBuffer.clear();
            Reflect.call(getIntegervBuffer, null, GL_VIEWPORT, viewportBuffer);
            out[0] = viewportBuffer.get(0);
            out[1] = viewportBuffer.get(1);
            out[2] = viewportBuffer.get(2);
            out[3] = viewportBuffer.get(3);
            return out;
        }
        // 两种形态都没有：按契约返回 null（表示「无法查询」），而不是伪装成 0x0 的视口。
        return getIntegerv == null ? null : out;
    }

    /**
     * 设置裁剪矩形（对应 {@code glScissor}）。
     *
     * <p>注意参数为窗口像素且原点在窗口<b>左下角</b>，与 GUI 坐标系（原点左上）y 轴相反，
     * 换算由调用方 {@link GlRenderer} 负责。
     */
    public void scissor(int x, int y, int width, int height) {
        Reflect.call(scissor, null, x, y, width, height);
    }

    /**
     * 是否具备接管坐标系所需的方法（{@code glMatrixMode} / {@code glLoadIdentity} / {@code glOrtho}）。
     *
     * <p>三者缺一，渲染器就无法建立自己的正交投影，只能继承游戏当前的矩阵，画面通常落在裁剪空间之外
     * 而完全不可见——所以这个状态必须能被诊断出来，而不是静默退化。
     */
    public boolean hasMatrixControl() {
        return matrixMode != null && loadIdentity != null && ortho != null;
    }

    /** 是否支持 {@code glScissor} 裁剪（需要 {@code glScissor} 加上任一种视口查询形态）。 */
    public boolean hasScissor() {
        return scissor != null && (getIntegerv != null || getIntegervBuffer != null);
    }

    /** 选第一个非空的方法句柄，供同义重载（LWJGL2 / LWJGL3 命名差异）之间回退。 */
    private static Method orElse(Method preferred, Method fallback) {
        return preferred != null ? preferred : fallback;
    }

    /**
     * 便捷方法：用立即模式绘制一个填充矩形。
     *
     * <p>顶点顺序为「左上 → 右上 → 右下 → 左下」，与 {@link GlRenderer#beginFrame()}
     * 建立的 y 轴向下投影一致；旧实现按 y 轴朝上排列，与投影互为反面。
     *
     * <p>绘制前显式关闭 {@code GL_TEXTURE_2D}：游戏字体渲染器会在两次绘制之间把它打开，
     * 若不在每个图元批次前复位，后续所有无纹理坐标的图元都会去采样字体图集。
     */
    public void fillRect(float x, float y, float width, float height, float r, float g, float b, float a) {
        disable(GL_TEXTURE_2D);
        color(r, g, b, a);
        begin(GL_QUADS);
        vertex(x, y);
        vertex(x + width, y);
        vertex(x + width, y + height);
        vertex(x, y + height);
        end();
    }

    /**
     * 便捷方法：用 {@code GL_LINE_LOOP} 绘制一个线宽可设的描边矩形。
     *
     * <p>与 {@link #fillRect} 一样，先关闭纹理，避免描边顶点采样字体图集。
     * 线宽的还原由调用方负责（见 {@link GlRenderer#outline}）。
     */
    public void strokeRect(float x, float y, float width, float height, float lineWidth,
                           float r, float g, float b, float a) {
        disable(GL_TEXTURE_2D);
        color(r, g, b, a);
        this.lineWidth(lineWidth);
        begin(GL_LINE_LOOP);
        vertex(x, y);
        vertex(x + width, y);
        vertex(x + width, y + height);
        vertex(x, y + height);
        end();
    }
}
