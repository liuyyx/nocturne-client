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
    /** 当前裁剪嵌套深度；为 0 表示未启用裁剪。 */
    private int clipDepth;

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
        if (!gl.hasMatrixControl()) {
            // 没有矩阵控制能力时无法接管坐标系，只能维持原状；日志里会显示 backend 可用但画面异常。
            return;
        }
        int[] viewport = gl.viewport();
        if (viewport != null && viewport[2] > 0 && viewport[3] > 0) {
            viewportWidth = viewport[2];
            viewportHeight = viewport[3];
        }

        gl.matrixMode(GlApi.GL_PROJECTION);
        gl.pushMatrix();
        gl.loadIdentity();
        // 下边界传 viewportHeight、上边界传 0：把 y 轴翻转成向下增长，与屏幕坐标一致。
        gl.ortho(0d, viewportWidth, viewportHeight, 0d, -1d, 1d);

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
        if (!statePushed) {
            return;
        }
        gl.matrixMode(GlApi.GL_PROJECTION);
        gl.popMatrix();
        gl.matrixMode(GlApi.GL_MODELVIEW);
        gl.popMatrix();
        if (clipDepth > 0) {
            // 组件树提前结束绘制（例如抛异常被上层吞掉）时，裁剪状态不能泄漏到下一帧。
            gl.disable(GlApi.GL_SCISSOR_TEST);
            clipDepth = 0;
        }
        statePushed = false;
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
        if (!gl.hasScissor() || clipDepth == MAX_CLIP_DEPTH) {
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
        int[] box = clipStack[clipDepth - 1];
        gl.scissor(box[0], box[1], box[2], box[3]);
    }

    /**
     * 把 GUI 坐标下的矩形换算成 {@code glScissor} 参数。
     *
     * <p>两处差异必须处理：GL 的裁剪原点在窗口左下角（y 轴向上），而 GUI 原点在左上角；
     * 且 {@code glScissor} 只接受整数。越界的裁剪框交给 GL 自行处理，这里只保证宽高非负。
     */
    private int[] toScissorBox(float x, float y, float width, float height) {
        int sx = Math.round(x);
        int sw = Math.max(0, Math.round(width));
        int sh = Math.max(0, Math.round(height));
        int sy = Math.max(0, viewportHeight - Math.round(y + height));
        return new int[]{sx, sy, sw, sh};
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
