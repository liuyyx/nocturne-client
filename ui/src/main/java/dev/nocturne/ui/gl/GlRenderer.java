package dev.nocturne.ui.gl;

import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;

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
    /** @return 进入时的 {@code GL_SHADE_MODEL} 原始值（诊断用，0 表示读不到） */
    private int shadeModelValue() {
        int[] value = gl.intQuery(GlApi.GL_SHADE_MODEL, 1);
        return value == null || value.length == 0 ? 0 : value[0];
    }

    /** 是否已打印过进入时的能力位快照。 */
    private static boolean capsLogged;
    /** 嵌套裁剪的矩形栈，元素为 {@code {x, y, width, height}}（GL 坐标系，原点左下）。 */
    private final int[][] clipStack = new int[MAX_CLIP_DEPTH][];
    /**
     * 本帧开始时游戏绑定的 2D 纹理（-1 = 未取到）。
     *
     * <p>我们的图集绑定是直接 {@code glBindTexture}，绕过了游戏 {@code GlStateManager} 的绑定缓存；
     * 若不还原，游戏会认为它自己的纹理仍绑定着、跳过重绑，随后所有绘制都采样我们的图集。
     * 1.8.9 真机症状：一开界面整个背景变白。
     */
    private int savedTextureBinding = -1;











    /** 当前裁剪嵌套深度（含因栈满被忽略的层）；为 0 表示未启用裁剪。 */
    private int clipDepth;
    /** 裁剪栈溢出是否已提示过；保证只打印一次，避免每帧刷屏。 */
    private boolean clipOverflowWarned;

    /**
     * UI 缩放的基准高度（物理像素）＝ 当前界面设计稿的高度。
     *
     * <p>界面按 744×540 的设计尺寸写，自身还会按绘制区再等比缩一次（见
     * {@code FaClickGui.updateScale}）。所以自动缩放要做的是「把逻辑尺寸放到接近 540 高」，
     * 而不是把小窗再除一遍——旧值 320 + 四舍五入会把 854×480 的窗口算成 2 倍（逻辑 427×240），
     * 设计缩放随之掉到 0.41，字只有 4px（真机反馈「字太小」）。
     */
    private static final int UI_BASE_HEIGHT = 540;

    /** 自动缩放上限：再大会让 1px 描边与圆角显得过粗。 */
    private static final int UI_MAX_SCALE = 8;

    /**
     * 当前生效的 UI 缩放系数（1 = 逐像素）。
     *
     * <p>它只影响两件事：{@link #viewportWidth}/{@link #viewportHeight} 记的是**逻辑尺寸**
     * （物理 / 缩放），据此建立的 {@code glOrtho} 与所有绘制坐标、以及 {@code width()}/{@code height()}
     * 的返回值三者保持一致；{@link #toScissorBox} 再乘回来换算成物理窗口坐标。
     *
     * <p>注意：之前那次"逻辑尺寸折算"之所以让叠加层完全不可见，是因为只改了 ortho 而
     * {@code width()} 仍返回物理尺寸——组件树按物理宽高布局、却被画在逻辑坐标系里，于是全部落在
     * 视口之外。这次三者同源，不会重演。
     */
    private volatile int uiScale = 1;

    /**
     * 用户手动指定的缩放；{@code 0} = 用 {@link #computeUiScale} 的自动值。
     *
     * <p>只存"用户想要几倍"，不当场改 {@link #uiScale}：缩放决定了逻辑尺寸，而当前帧的组件树已经
     * 按旧尺寸排好、命中测试也基于旧坐标——半途改会让这一帧的绘制与输入错位。新值由下一帧的
     * {@link #beginFrame()} 统一应用。
     */
    private volatile int scaleOverride;
    /** 视口读取失败的诊断是否已打印过；保证只打印一次。 */
    private final java.util.concurrent.atomic.AtomicBoolean viewportLogged =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 首帧布局诊断是否已打印过；之后仅在尺寸/缩放变化时再打（窗口 resize、全屏切换）。 */
    private volatile int loggedRawWidth = -1;
    private volatile int loggedRawHeight = -1;
    private volatile int loggedScale = -1;

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

    @Override
    public int scale() {
        return uiScale;
    }

    @Override
    public void setScaleOverride(int scale) {
        int next = scale <= 0 ? 0 : Math.min(scale, UI_MAX_SCALE);
        if (next == scaleOverride) {
            return;
        }
        scaleOverride = next;
        // 只记不动：本帧余下的绘制仍按旧逻辑尺寸（见 scaleOverride 的注释），下一帧生效。
    }

    /** @return 手动覆盖的缩放；{@code 0} 表示"按屏幕高度自动"（供界面提示当前处于哪一档）。 */
    public int scaleOverride() {
        return scaleOverride;
    }

    /**
     * 定出本帧的缩放：用户手动值优先，否则按屏幕高度自动。
     *
     * @param rawWidth  物理视口宽
     * @param rawHeight 物理视口高
     * @return 1..{@link #UI_MAX_SCALE} 的整数倍
     */
    private int resolveUiScale(int rawWidth, int rawHeight) {
        int override = scaleOverride;
        return override > 0 ? override : computeUiScale(rawWidth, rawHeight);
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
        // 诊断必须在 hasMatrixControl 检查之前：检查失败会提前返回，若诊断在其后就永远看不到输出，
        // 而「改了尺寸却完全不可见」正是这种情形——必须先能区分两条路径。
        boolean matrixControl = gl.hasMatrixControl();
        if (!matrixControl) {
            if (loggedScale != 0) {
                loggedScale = 0;
                System.out.println("[nocturne] gl renderer: hasMatrixControl=false"
                        + " (matrixMode/loadIdentity/ortho handle missing); layout stays "
                        + viewportWidth + "x" + viewportHeight);
            }
            return;
        }
        int[] rawViewport = gl.viewport();
        if (rawViewport != null && rawViewport[2] > 0 && rawViewport[3] > 0) {
            // 逻辑尺寸 = 物理 / uiScale：ortho、绘制坐标、width()/height() 三者同源（见 uiScale 注释）。
            // 固定像素尺寸的界面在高分屏上"十分小"，就是缺这一层缩放。
            uiScale = resolveUiScale(rawViewport[2], rawViewport[3]);
            viewportWidth = Math.max(1, rawViewport[2] / uiScale);
            viewportHeight = Math.max(1, rawViewport[3] / uiScale);
        }
        // 只在「尺寸或缩放变化」时打印：窗口 resize / 全屏切换后必须能看到新的真实值，
        // 只打首帧会让「界面没跟着放大」这类现象无从判断（首帧往往还是启动时的小窗口）。
        if (rawViewport != null
                && (loggedRawWidth != rawViewport[2] || loggedRawHeight != rawViewport[3]
                    || loggedScale != uiScale)) {
            loggedRawWidth = rawViewport[2];
            loggedRawHeight = rawViewport[3];
            loggedScale = uiScale;
            System.out.println("[nocturne] gl renderer: raw=" + java.util.Arrays.toString(rawViewport)
                    + " layout=" + viewportWidth + "x" + viewportHeight
                    + " uiScale=" + uiScale);
        }
        // 视口未知或退化时不能调用 glOrtho：左右相等 / 上下相等在 GL 里是非法的（GL_INVALID_VALUE），
        // 该调用会被丢弃、矩阵保持原样，GUI 于是永久画在裁剪空间之外——必须留痕而不是静默。
        boolean haveViewport = viewportWidth > 0 && viewportHeight > 0;
        if (!haveViewport) {
            reportMissingViewport(rawViewport);
        }

        // 属性栈保存（pushAttrib/popAttrib）**故意不用**：这两个是直接 GL 调用，GlStateManager 的
        // 缓存不参与——"恢复真实状态"的同时缓存还停在被改过的值上，于是下一帧游戏按缓存判断
        // "不用再设置"，实际状态却是旧的，字形/纹理就画不出来（鼠标一动触发的重绘才会刷对缓存，
        // 表现为"文字要鼠标移上去才出现"）。状态改动一律经 {@link GlApi} → GlStateManager（缓存同步），
        // 游戏每帧会自行设置它需要的那几项，无需我们回滚。

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

        // 立即模式的前提：客户态数组全部关闭（否则 glBegin/glVertex2f 被静默忽略）。
        gl.disableClientArrays();
        // 游戏会把着色模型留成 FLAT（不还原），那样逐顶点渐变会塌成单色。
        gl.shadeModel(GlApi.GL_SMOOTH);
        // 活动纹理单元与纹理矩阵都是全局状态：1.8.9 的光照贴图会在 1 号单元上绑定/启用，
        // 且 EntityRenderer 会切到 GL_TEXTURE 矩阵后不还原。不在这里复位的话，
        // 我们绑定到「错误的单元」或纹理坐标被残留矩阵变换，纹理四边形就会采错区域。
        gl.activeTexture(GlApi.GL_TEXTURE0);
        gl.matrixMode(GlApi.GL_TEXTURE);
        gl.loadIdentity();
        gl.matrixMode(GlApi.GL_MODELVIEW);
        if (!capsLogged && System.getProperty("nocturne.ui.debug") != null) {
            capsLogged = true;
            System.out.println("[nocturne] incoming caps: alphaTest=" + gl.isCapEnabled(0x0BC0)
                    + " lighting=" + gl.isCapEnabled(0x0B50)
                    + " colorMaterial=" + gl.isCapEnabled(0x0B57)
                    + " scissor=" + gl.isCapEnabled(GlApi.GL_SCISSOR_TEST)
                    + " depth=" + gl.isCapEnabled(GlApi.GL_DEPTH_TEST)
                    + " cull=" + gl.isCapEnabled(GlApi.GL_CULL_FACE)
                    + " texture2d=" + gl.isCapEnabled(GlApi.GL_TEXTURE_2D)
                    + " blend=" + gl.isCapEnabled(GlApi.GL_BLEND)
                    + " shadeModel=" + shadeModelValue());
        }
        // 上一帧（世界覆盖层/组件树）可能留下裁剪测试：若 scissor 盒退化成 0 尺寸，
        // 本帧所有绘制都会被静默裁掉（不报错、颜色矩阵都对，就是看不见）。
        // 本帧的裁剪由 pushClip/popClip 重新开启，所以这里关掉是安全的。
        gl.disable(GlApi.GL_SCISSOR_TEST);
        gl.disable(GlApi.GL_DEPTH_TEST);
        gl.disable(GlApi.GL_CULL_FACE);
        gl.disable(GlApi.GL_TEXTURE_2D);
        gl.enable(GlApi.GL_BLEND);
        gl.blendFunc(GlApi.GL_SRC_ALPHA, GlApi.GL_ONE_MINUS_SRC_ALPHA);
        // 绑定也必须还原（见 savedTextureBinding）：GL 的绑定与游戏缓存是两套状态。
        savedTextureBinding = gl.boundTexture();
        statePushed = true;
    }

    /**
     * 还原 {@link #beginFrame()} 压入的矩阵，并解除本帧可能残留的裁剪。
     *
     * <p>只恢复矩阵栈：其余开关（深度测试、纹理、剔除）由游戏每帧自行重设，逐个查询原值再还原
     * 反而会在热路径上引入多次 {@code glGet*} 调用。
     */
    /**
     * 取出上一秒的绘制统计（诊断用）。
     *
     * @return {@code {批次数, 顶点数}}
     */
    public int[] takeBatchCounts() {
        return gl.takeBatchCounts();
    }

    /**
     * 取出上一秒的纹理上传统计（诊断用）。
     *
     * @return {@code {整张上传次数, 子区域上传次数}}
     */
    public int[] takeUploadCounts() {
        return gl.takeUploadCounts();
    }

    @Override
    public void endFrame() {
        if (statePushed) {
            gl.matrixMode(GlApi.GL_PROJECTION);
            gl.popMatrix();
            gl.matrixMode(GlApi.GL_MODELVIEW);
            gl.popMatrix();
            // 把真实绑定还原成游戏以为的那个：游戏缓存从不参与我们的 glBindTexture，
            // 恢复真实状态等于让两套状态重新对齐（否则背景变白/纹理错乱）。
            if (savedTextureBinding >= 0) {
                gl.bindTexture2D(savedTextureBinding);
                savedTextureBinding = -1;
            }
            // 这里**不再** popAttrib：状态改动都经 GlStateManager（缓存同步），
            // 属性栈回滚只会把真实状态与缓存再拆开一次（见 beginFrame 的说明）。
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

    /**
     * 固定管线的纹理支路：上传/采样走 {@link GlApi}（它在本代同时负责把
     * {@code GL_UNPACK_ALIGNMENT} 与采样参数设成固定值）。
     *
     * <p>{@link #texturedQuad} 用立即模式画四边形，{@code argb} 走 {@code glColor4f} 调制；
     * 混合是 {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}（非预乘），因此上传的像素必须是非预乘 ARGB
     * ——{@code FontRasterizer} 的白化字形与 {@code RasterTextures} 的 alpha 场都遵守这一约定。
     */
    @Override
    public boolean supportsTextures() {
        return gl.hasTextures();
    }

    @Override
    public int uploadTexture(int id, int width, int height, int[] argb) {
        if (!gl.hasTextures() || width <= 0 || height <= 0 || argb == null) {
            return id;
        }
        int handle = id > 0 ? id : gl.genTexture();
        if (handle <= 0) {
            return id;
        }
        gl.bindTexture2D(handle);
        gl.texParameteri(GlApi.GL_TEXTURE_2D, GlApi.GL_TEXTURE_MIN_FILTER, GlApi.GL_NEAREST);
        gl.texParameteri(GlApi.GL_TEXTURE_2D, GlApi.GL_TEXTURE_MAG_FILTER, GlApi.GL_NEAREST);
        gl.texParameteri(GlApi.GL_TEXTURE_2D, GlApi.GL_TEXTURE_WRAP_S, GlApi.GL_CLAMP_TO_EDGE);
        gl.texParameteri(GlApi.GL_TEXTURE_2D, GlApi.GL_TEXTURE_WRAP_T, GlApi.GL_CLAMP_TO_EDGE);
        if (!gl.uploadTexture(width, height, argb)) {
            if (handle != id) {
                // 新建失败：把刚生成的句柄还回去，避免句柄泄漏。
                gl.deleteTexture(handle);
            }
            return id;
        }
        return handle;
    }

    /**
     * 局部更新纹理（{@code glTexSubImage2D}）：图集新增字形时只传变化的那一小块。
     *
     * <p>不允许落到整张上传的旧路径——那正是首帧卡顿的来源（1024² = 4 MB × 每个新字形）。
     */
    @Override
    public int uploadTextureRegion(int texture, int width, int height, int[] argb,
                                   int x, int y, int regionWidth, int regionHeight) {
        if (texture <= 0 || !gl.hasTextures() || argb == null
                || regionWidth <= 0 || regionHeight <= 0) {
            return uploadTexture(texture, width, height, argb);
        }
        gl.bindTexture2D(texture);
        return gl.uploadTextureRegion(x, y, regionWidth, regionHeight, width, argb)
                ? texture : uploadTexture(texture, width, height, argb);
    }

    @Override
    public void deleteTexture(int id) {
        if (id <= 0) {
            return;   // 契约：id<=0 忽略（与另外两个后端一致）
        }
        gl.deleteTexture(id);
    }

    /**
     * 逐顶点渐变四边形：固定管线的 {@code GL_SMOOTH} 会对顶点色做双线性插值，
     * 因此一次 {@code GL_QUADS} 就能得到真正平滑的渐变（不必切成平涂小矩形）。
     *
     * <p>顶点序与 {@code GlApi.fillRect} 一致（投影翻转 y 后为正面序）。
     */
    @Override
    public boolean gradientQuad(float x, float y, float width, float height, Color topLeft,
                                Color topRight, Color bottomRight, Color bottomLeft) {
        if (width <= 0f || height <= 0f) {
            return true;   // 空矩形：按契约跳过，但仍算「已处理」
        }
        gl.beginQuads(0);
        vertexColor(bottomLeft);
        gl.vertex(x, y + height);
        vertexColor(bottomRight);
        gl.vertex(x + width, y + height);
        vertexColor(topRight);
        gl.vertex(x + width, y);
        vertexColor(topLeft);
        gl.vertex(x, y);
        return true;
    }

    /** 把颜色交给批次（{@code null} 视为全透明）。 */
    private void vertexColor(Color color) {
        if (color == null) {
            gl.color(0f, 0f, 0f, 0f);
            return;
        }
        gl.color(color.rf(), color.gf(), color.bf(), color.af());
    }

    @Override
    public void texturedQuad(int texture, float x, float y, float width, float height,
                             float u0, float v0, float u1, float v1, int argb) {
        if (texture <= 0 || width <= 0f || height <= 0f || (argb >>> 24) == 0) {
            return;
        }
        // 纹理状态由批次负责（同一张图集的连续字形合进同一个批次）；离开纹理批次时由
        // ensureBatch 统一 flush 并关掉 GL_TEXTURE_2D，因此这里不需要 try/finally。
        gl.beginTexturedQuads(texture);
        gl.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f);
        // 顶点序与纯色四边形保持一致（正面序，见 GlApi.fillRect 的说明）；纹理坐标跟着顶点走。
        gl.texCoord(u0, v1);
        gl.vertex(x, y + height);
        gl.texCoord(u1, v1);
        gl.vertex(x + width, y + height);
        gl.texCoord(u1, v0);
        gl.vertex(x + width, y);
        gl.texCoord(u0, v0);
        gl.vertex(x, y);
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
                System.err.println("[nocturne] clip stack overflow (depth " + clipDepth
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
        // GUI 坐标是**逻辑**坐标（已按 uiScale 缩小），而 glScissor 用的是窗口物理像素：
        // 这里必须乘回去，否则裁剪框会缩在左上角（放大后内容被裁掉大半）。
        int scale = uiScale;
        int x0 = Math.round(x * scale);
        int y0 = Math.round(y * scale);
        int x1 = x0 + Math.max(0, Math.round(width * scale));
        int y1 = y0 + Math.max(0, Math.round(height * scale));
        int limitWidth = viewportWidth * scale;
        int limitHeight = viewportHeight * scale;
        if (limitWidth > 0 && limitHeight > 0) {
            x0 = clamp(x0, 0, limitWidth);
            x1 = clamp(x1, 0, limitWidth);
            y0 = clamp(y0, 0, limitHeight);
            y1 = clamp(y1, 0, limitHeight);
            return new int[]{x0, limitHeight - y1, x1 - x0, y1 - y0};
        }
        // 视口未知时不与视口求交，仅保证宽高非负。
        int sx = Math.max(0, x0);
        int sw = Math.max(0, x1 - x0);
        int sh = Math.max(0, y1 - y0);
        int sy = Math.max(0, limitHeight - y1);
        return new int[]{sx, sy, sw, sh};
    }

    /**
     * 按物理视口算 UI 缩放系数（见 {@link #UI_BASE_HEIGHT} 的取值理由）。
     *
     * @param rawWidth  物理视口宽（像素）
     * @param rawHeight 物理视口高（像素）
     * @return 1..{@link #UI_MAX_SCALE} 的整数倍
     */
    private static int computeUiScale(int rawWidth, int rawHeight) {
        int shorter = Math.min(rawWidth, rawHeight);
        // 向下取整：宁可让界面保持设计尺寸（并在绘制区里等比缩小一点），也不要把字缩到看不清。
        int scale = Math.max(1, shorter / UI_BASE_HEIGHT);
        return Math.min(scale, UI_MAX_SCALE);
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
        System.out.println("[nocturne] fixed-pipeline renderer cannot read GL_VIEWPORT ("
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
        // 无纹理扇形批次：与相邻的圆角条带合批，批次内的颜色逐顶点设置。
        gl.beginFan(0);
        gl.color(r, g, b, a);
        // 扇形绕序同样按正面序走（角度递减）：投影翻转 y 轴后，角度递增方向的扇形是背面。
        gl.vertex(cx, cy);
        for (int i = CORNER_STEPS; i >= 0; i--) {
            double angle = Math.toRadians(startDeg + (endDeg - startDeg) * i / (double) CORNER_STEPS);
            gl.vertex(cx + (float) Math.cos(angle) * radius, cy + (float) Math.sin(angle) * radius);
        }
    }
}
