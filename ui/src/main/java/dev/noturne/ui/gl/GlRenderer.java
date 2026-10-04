package dev.noturne.ui.gl;

import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;

/**
 * 基于固定管线 OpenGL（通过 {@link GlApi}）的 {@link Renderer} 实现。
 *
 * <p>面向 1.8.9 时代的管线（LWJGL2 / OpenGL 1.x）：立即模式、无着色器。
 * 圆角由三个矩形加四个三角扇四分之一圆拼成——在 UI 这种小尺寸下已经够用，
 * 且不需要着色器或模板缓冲。
 */
public final class GlRenderer implements UiBackend {

    /** 每个 90° 圆角细分的扇形三角数；6 段在 UI 尺寸下已足够平滑。 */
    private static final int CORNER_STEPS = 6;

    /** 底层固定管线绑定；不得为 {@code null}。 */
    private final GlApi gl;
    /** 文本渲染器；构造时 {@code null} 会被替换为 {@link TextRenderer#NONE}。 */
    private final TextRenderer text;

    /** 裁剪栈的最大嵌套层数；超出后更深的裁剪被忽略，而不是扩容。 */
    private static final int MAX_CLIP_DEPTH = 8;

    /** 最近一次 {@link #beginFrame()} 取得的绘制区域宽度（像素）；未渲染过为 0。 */
    private int viewportWidth;
    /** 最近一次 {@link #beginFrame()} 取得的绘制区域高度（像素）；未渲染过为 0。 */
    private int viewportHeight;
    /** {@link #beginFrame()} 是否已压入矩阵栈；决定 {@link #endFrame()} 是否需要还原。 */
    private boolean statePushed;
    /** 嵌套裁剪的矩形栈，元素为 {@code {x, y, width, height}}（GL 坐标系，原点左下）。 */
    private final int[][] clipStack = new int[MAX_CLIP_DEPTH][];
    /** 当前裁剪嵌套深度（含因栈满被忽略的层）；为 0 表示未启用裁剪。 */
    private int clipDepth;
    /** 裁剪栈溢出是否已提示过；保证只打印一次，避免每帧刷屏。 */
    private boolean clipOverflowWarned;
    /** 视口读取失败的诊断是否已打印过；保证只打印一次。 */
    private final java.util.concurrent.atomic.AtomicBoolean viewportLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 构造渲染器。
     *
     * @param gl   固定管线绑定
     * @param text 文本渲染器，传 {@code null} 时退化为空实现
     */
    public GlRenderer(GlApi gl, TextRenderer text) {
        this.gl = gl;
        this.text = text == null ? TextRenderer.NONE : text;
    }

    /** @return 底层 GL 绑定，供调用方直接做矩阵等低层设置 */
    public GlApi gl() {
        return gl;
    }

    @Override
    public String backendName() {
        return "gl-fixed";
    }

    @Override
    public int width() {
        return viewportWidth;
    }

    @Override
    public int height() {
        return viewportHeight;
    }

    /**
     * {@inheritDoc}
     *
     * <p>固定管线后端只有在能接管矩阵栈时才画得出东西：拿不到 {@code glMatrixMode}/{@code glOrtho}
     * 就只能在游戏投影下绘制，画面通常完全不可见。
     */
    @Override
    public boolean ready() {
        return gl.hasMatrixControl();
    }

    /**
     * 接管 GL 状态，为 UI 建立「像素坐标、原点左上、y 轴向下」的坐标系。
     *
     * <p>这一步是固定管线路径能看见东西的前提：帧交换点处的投影矩阵仍属于游戏（通常是透视投影 +
     * 相机变换），直接按屏幕坐标绘制会把整个 GUI 送到裁剪空间之外。
     *
     * <p>做法是把投影与模型视图两个矩阵栈各压入一层后重置为单位矩阵，再用视口尺寸建立正交投影；
     * {@link #endFrame()} 负责还原。深度测试、纹理与剔除在此关闭，混合按 alpha 打开。
     */
    @Override
    public void beginFrame() {
        if (statePushed) {
            // 上一帧的 endFrame 没有执行（例如组件树抛异常被上层吞掉）：
            // 先把残留状态还原，避免矩阵栈每帧净泄漏两层、约 16 帧后永久损坏游戏投影。
            endFrame();
        }
        if (!gl.hasMatrixControl()) {
            // 没有矩阵控制能力时无法接管坐标系，只能维持原状；日志里会显示 backend 可用但画面异常。
            return;
        }
        int[] viewport = gl.viewport();
        if (viewport != null && viewport[2] > 0 && viewport[3] > 0) {
            viewportWidth = viewport[2];
            viewportHeight = viewport[3];
        }
        // 视口未知或退化时不能调用 glOrtho：左右相等 / 上下相等在 GL 里是非法的（GL_INVALID_VALUE），
        // 该调用会被丢弃、矩阵保持原样，GUI 于是永久画在裁剪空间之外——必须留痕而不是静默。
        boolean haveViewport = viewportWidth > 0 && viewportHeight > 0;
        if (!haveViewport) {
            reportMissingViewport(viewport);
        }

        gl.matrixMode(GlApi.GL_PROJECTION);
        gl.pushMatrix();
        gl.loadIdentity();
        if (haveViewport) {
            // 下边界传 viewportHeight、上边界传 0：把 y 轴翻转成向下增长，与屏幕坐标一致。
            gl.ortho(0d, viewportWidth, viewportHeight, 0d, -1d, 1d);
        }

        gl.matrixMode(GlApi.GL_MODELVIEW);
        gl.pushMatrix();
        gl.loadIdentity();

        gl.disable(GlApi.GL_DEPTH_TEST);
        gl.disable(GlApi.GL_CULL_FACE);
        gl.disable(GlApi.GL_TEXTURE_2D);
        gl.enable(GlApi.GL_BLEND);
        gl.blendFunc(GlApi.GL_SRC_ALPHA, GlApi.GL_ONE_MINUS_SRC_ALPHA);
        statePushed = true;
    }

    /**
     * 还原 {@link #beginFrame()} 压入的矩阵，并解除本帧可能残留的裁剪。
     *
     * <p>只恢复矩阵栈：其余开关（深度测试、纹理、剔除）由游戏每帧自行重设，逐个查询原值再还原
     * 反而会在热路径上引入多次 {@code glGet*} 调用。
     */
    @Override
    public void endFrame() {
        if (statePushed) {
            gl.matrixMode(GlApi.GL_PROJECTION);
            gl.popMatrix();
            gl.matrixMode(GlApi.GL_MODELVIEW);
            gl.popMatrix();
            statePushed = false;
        }
        // 裁剪清理不得被 statePushed 门控：beginFrame 可能因缺少矩阵句柄提前返回，
        // 而 pushClip 早已打开 GL_SCISSOR_TEST，此时若不解除，裁剪区域会逐帧收缩直至宽高为 0。
        if (clipDepth > 0) {
            // 组件树提前结束绘制（例如抛异常被上层吞掉）时，裁剪状态不能泄漏到下一帧。
            gl.disable(GlApi.GL_SCISSOR_TEST);
            clipDepth = 0;
        }
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        if (width <= 0f || height <= 0f || color == null) {
            return;
        }
        gl.fillRect(x, y, width, height, color.rf(), color.gf(), color.bf(), color.af());
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        if (width <= 0f || height <= 0f || color == null) {
            return;
        }
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r <= 0.5f) {
            rect(x, y, width, height, color);
            return;
        }
        float cr = color.rf();
        float cg = color.gf();
        float cb = color.bf();
        float ca = color.af();

        // 中间横带，再加左右两条竖带
        gl.fillRect(x + r, y, width - 2f * r, height, cr, cg, cb, ca);
        gl.fillRect(x, y + r, r, height - 2f * r, cr, cg, cb, ca);
        gl.fillRect(x + width - r, y + r, r, height - 2f * r, cr, cg, cb, ca);

        // 四个四分之一圆
        quarter(x + r, y + r, r, 180f, 270f, cr, cg, cb, ca);
        quarter(x + width - r, y + r, r, 270f, 360f, cr, cg, cb, ca);
        quarter(x + width - r, y + height - r, r, 0f, 90f, cr, cg, cb, ca);
        quarter(x + r, y + height - r, r, 90f, 180f, cr, cg, cb, ca);
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        if (width <= 0f || height <= 0f || color == null) {
            return;
        }
        gl.strokeRect(x, y, width, height, lineWidth, color.rf(), color.gf(), color.bf(), color.af());
        // glLineWidth 是全局状态：不还原会把线宽泄漏给游戏后续的线段绘制（默认值为 1）。
        gl.lineWidth(1f);
    }

    @Override
    public void text(String value, float x, float y, float size, Color color) {
        if (value == null || value.isEmpty()) {
            return;
        }
        text.draw(value, x, y, size, color);
    }

    @Override
    public float textWidth(String value, float size) {
        return text.width(value, size);
    }

    @Override
    public float textHeight(float size) {
        return text.height(size);
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        if (!gl.hasScissor()) {
            return;
        }
        if (clipDepth >= MAX_CLIP_DEPTH) {
            // 栈满时仍要记账：否则 popClip 会把深度弹到与实际不一致的位置，
            // 导致此后每层裁剪都提前一层失效（越界绘制）。更深层不再改变 GL 状态。
            if (!clipOverflowWarned) {
                clipOverflowWarned = true;
                System.err.println("[noturne] clip stack overflow (depth " + clipDepth
                        + " >= " + MAX_CLIP_DEPTH + "); deeper clips ignored");
            }
            clipDepth++;
            return;
        }
        int[] box = toScissorBox(x, y, width, height);
        if (clipDepth > 0) {
            // 嵌套裁剪取交集，否则内层会覆盖外层的限制
            box = intersection(clipStack[clipDepth - 1], box);
        }
        clipStack[clipDepth++] = box;
        gl.scissor(box[0], box[1], box[2], box[3]);
        gl.enable(GlApi.GL_SCISSOR_TEST);
    }

    @Override
    public void popClip() {
        if (clipDepth == 0) {
            return;
        }
        clipDepth--;
        if (clipDepth == 0) {
            gl.disable(GlApi.GL_SCISSOR_TEST);
            return;
        }
        if (clipDepth < MAX_CLIP_DEPTH) {
            int[] box = clipStack[clipDepth - 1];
            gl.scissor(box[0], box[1], box[2], box[3]);
        }
        // clipDepth >= MAX_CLIP_DEPTH 时活动裁剪仍是已下发的栈顶矩形，无需重下发。
    }

    /**
     * 把 GUI 坐标下的矩形换算成 {@code glScissor} 参数。
     *
     * <p>两处差异必须处理：GL 的裁剪原点在窗口左下角（y 轴向上），而 GUI 原点在左上角；
     * 且 {@code glScissor} 只接受整数。越界的裁剪框必须与视口求交——旧实现只把下边界夹到 0，
     * 会把视口上方/下方本应裁掉的区域整体移进画面。
     */
    private int[] toScissorBox(float x, float y, float width, float height) {
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = x0 + Math.max(0, Math.round(width));
        int y1 = y0 + Math.max(0, Math.round(height));
        if (viewportWidth > 0 && viewportHeight > 0) {
            x0 = clamp(x0, 0, viewportWidth);
            x1 = clamp(x1, 0, viewportWidth);
            y0 = clamp(y0, 0, viewportHeight);
            y1 = clamp(y1, 0, viewportHeight);
            return new int[]{x0, viewportHeight - y1, x1 - x0, y1 - y0};
        }
        // 视口未知时不与视口求交，仅保证宽高非负。
        int sx = Math.max(0, x0);
        int sw = Math.max(0, x1 - x0);
        int sh = Math.max(0, y1 - y0);
        int sy = Math.max(0, viewportHeight - y1);
        return new int[]{sx, sy, sw, sh};
    }

    /** 把 {@code value} 夹取到 {@code [lo, hi]}。 */
    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }

    /**
     * 视口读不出来时的诊断：只打印一次，说明「GUI 不会显示」的原因，避免每帧刷屏。
     *
     * <p>这是 1.8.9（LWJGL2）上唯一能解释「注入成功但界面全无」的线索：{@code GL11} 只提供
     * {@code glGetInteger(int, IntBuffer)}，早期实现按 LWJGL3 的 {@code (int, int[])} 查找，
     * 于是视口恒为 0x0。
     */
    private void reportMissingViewport(int[] viewport) {
        if (!viewportLogged.compareAndSet(false, true)) {
            return;
        }
        System.out.println("[noturne] fixed-pipeline renderer cannot read GL_VIEWPORT ("
                + (viewport == null ? "no query method bound" : "reported 0x0")
                + "); the overlay will not be visible. On LWJGL2 the query must use "
                + "glGetInteger(int, IntBuffer).");
    }

    /** 计算两个裁剪矩形的交集；不相交时宽高为 0。 */
    private static int[] intersection(int[] a, int[] b) {
        int x1 = Math.max(a[0], b[0]);
        int y1 = Math.max(a[1], b[1]);
        int x2 = Math.min(a[0] + a[2], b[0] + b[2]);
        int y2 = Math.min(a[1] + a[3], b[1] + b[3]);
        return new int[]{x1, y1, Math.max(0, x2 - x1), Math.max(0, y2 - y1)};
    }

    /**
     * 绘制一个 90° 的四分之一圆，作为圆角的组成部分。
     *
     * @param cx,cy  圆心
     * @param radius 半径
     * @param startDeg 起始角度（度）
     * @param endDeg   结束角度（度）
     * @param r,g,b,a 颜色分量，0–1
     */
    private void quarter(float cx, float cy, float radius, float startDeg, float endDeg,
                         float r, float g, float b, float a) {
        // 文字绘制会把 GL_TEXTURE_2D 打开，扇形批次同样不能带着它绘制。
        gl.disable(GlApi.GL_TEXTURE_2D);
        gl.color(r, g, b, a);
        gl.begin(GlApi.GL_TRIANGLE_FAN);
        gl.vertex(cx, cy);
        for (int i = 0; i <= CORNER_STEPS; i++) {
            double angle = Math.toRadians(startDeg + (endDeg - startDeg) * i / (double) CORNER_STEPS);
            gl.vertex(cx + (float) Math.cos(angle) * radius, cy + (float) Math.sin(angle) * radius);
        }
        gl.end();
    }
}
