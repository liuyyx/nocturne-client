package dev.noturne.ui.gl;

import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;

/**
 * OpenGL 3.2 核心 profile（Minecraft 1.13+ / 26.x）下的 {@link Renderer} 实现。
 *
 * <p>固定管线的 {@link GlRenderer} 在这里行不通：核心 profile 已经移除立即模式。
 * 所有图形都在 CPU 侧展开成三角形，上传到同一个可复用的 VBO，
 * 再用一个只有两个 uniform（投影矩阵 + 纯色）的着色器绘制。
 * 颜色走 uniform 而非逐顶点，是因为 UI 画的都是平涂——在这个规模上，
 * 为渐变去做批处理的成本大于收益。
 *
 * <p>它是三个可互换后端之一（另见 {@link GlRenderer} 与 {@link SkijaBackend}，
 * 由 {@code OverlayBootstrap} 按运行环境选择）。
 */
public final class ModernRenderer implements UiBackend {

    /** 顶点着色器：把二维屏幕坐标乘以正交投影矩阵后输出裁剪空间坐标。 */
    private static final String VERTEX_SHADER =
            "#version 150 core\n"
                    + "in vec2 aPos;\n"
                    + "uniform mat4 uProjection;\n"
                    + "void main() {\n"
                    + "    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);\n"
                    + "}\n";

    /** 片元着色器：输出常量颜色，对应 UI 的平涂风格。 */
    private static final String FRAGMENT_SHADER =
            "#version 150 core\n"
                    + "uniform vec4 uColor;\n"
                    + "out vec4 fragColor;\n"
                    + "void main() {\n"
                    + "    fragColor = uColor;\n"
                    + "}\n";

    /** 顶点属性 {@code aPos} 在着色器中的固定位置；链接前用 glBindAttribLocation 绑定。 */
    private static final int ATTRIB_POSITION = 0;

    /** 每个 90° 圆角细分的段数；与固定管线后端保持一致，保证两条路径观感相同。 */
    private static final int CORNER_SEGMENTS = 6;

    /** 裁剪栈的最大嵌套层数；超出后更深的裁剪被忽略，但深度仍记账以保证 push/pop 配对。 */
    private static final int MAX_CLIP_DEPTH = 8;

    /** 核心 profile 绑定；不得为 {@code null}。 */
    private final ModernGlApi gl;
    /** 文本渲染器；构造时 {@code null} 会被替换为 {@link TextRenderer#NONE}。 */
    private final TextRenderer text;
    /** 游戏桥（取 Window 帧缓冲尺寸用）；{@code null} 时只用 GL 视口。 */
    private final dev.noturne.client.game.GameBridge bridge;

    /** 着色器程序 id。 */
    private int program;
    /** 顶点数组对象（VAO）id。 */
    private int vertexArray;
    /** 顶点缓冲对象（VBO）id，所有绘制复用它。 */
    private int vertexBuffer;
    /** {@code uProjection} uniform 的位置。 */
    private int projectionLocation;
    /** {@code uColor} uniform 的位置。 */
    private int colorLocation;
    /** 着色器与缓冲是否已创建成功；未就绪时所有绘制调用都会被跳过。 */
    private boolean ready;
    /** 初始化失败计数；达到上限后停止重试（避免无限重试的产生与清理泄漏）。 */
    private int initFailures;
    /** 初始化重试上限：超过即视为环境不支持，永久放弃（真实驱动失败不会短暂恢复）。 */
    private static final int MAX_INIT_FAILURES = 5;
    private long nextRetryNanos;

    /** 当前正交投影矩阵（16 个元素，列主序）。 */
    private float[] projection = new float[16];
    /** 缓存的视口原点 x（像素）。 */
    private int screenX;
    /** 缓存的视口原点 y（像素）。 */
    private int screenY;
    /** 缓存的视口宽度，用于检测视口变化。 */
    private int screenWidth;
    /** 缓存的视口高度，用于检测视口变化。 */
    private int screenHeight;

    /** 嵌套裁剪的矩形栈，元素为 {@code {x, y, width, height}}（GL 坐标系，原点左下）。 */
    private final int[][] clipStack = new int[MAX_CLIP_DEPTH][];
    /** 当前裁剪嵌套深度（含因栈满被忽略的层）；为 0 表示未启用裁剪。 */
    private int clipDepth;
    /** 裁剪栈溢出是否已提示过。 */
    private boolean clipOverflowWarned;

    /** 是否已保存 {@link #beginFrame()} 之前的开关状态；保存后由 {@link #endFrame()} 还原。 */
    private boolean stateSaved;
    /** beginFrame 之前的混合因子（GL_BLEND_SRC/DST_RGB/ALPHA 四项）。 */
    private int[] blendBefore;
    /** beginFrame 之前的深度测试状态。 */
    private boolean depthWasEnabled;
    /** beginFrame 之前的混合开关状态。 */
    private boolean blendWasEnabled;
    private boolean cullWasEnabled;

    /** 供每次绘制复用的暂存顶点缓冲，使整个渲染过程不产生逐帧分配。 */
    private float[] scratch = new float[256];

    /**
     * 构造渲染器。
     *
     * @param gl   核心 profile 绑定
     * @param text 文本渲染器，传 {@code null} 时退化为空实现
     */
    public ModernRenderer(ModernGlApi gl, TextRenderer text) {
        this(gl, text, null);
    }

    /**
     * 构造渲染器。
     *
     * @param gl     核心 profile 绑定
     * @param text   文本渲染器，传 {@code null} 时退化为空实现
     * @param bridge 游戏桥（取 Window 帧缓冲尺寸用，可为 {@code null}）
     */
    public ModernRenderer(ModernGlApi gl, TextRenderer text,
                          dev.noturne.client.game.GameBridge bridge) {
        this.gl = gl;
        this.text = text == null ? TextRenderer.NONE : text;
        this.bridge = bridge;
    }

    /** @return 底层 GL 绑定，供调用方做低层设置或诊断 */
    public ModernGlApi gl() {
        return gl;
    }

    /**
     * 编译着色器并创建缓冲对象。必须已有当前 GL 上下文时调用，且只需调用一次。
     *
     * <p>任一步失败都会删除已创建的 GL 对象并累加初始化失败计数，达到上限后永久放弃——
     * 否则 beginFrame 每帧新建 1~2 个着色器且永不删除，长期挂机会累积数千个 GL 对象。
     *
     * @return 是否初始化成功
     */
    public boolean initialise() {
        if (ready) {
            return true;
        }
        if (initFailures > 0 && System.nanoTime() < nextRetryNanos) {
            return false;
        }

        int vertex = gl.createShader(ModernGlApi.GL_VERTEX_SHADER);
        gl.shaderSource(vertex, VERTEX_SHADER);
        gl.compileShader(vertex);
        if (vertex == 0 || !gl.compileOk(vertex)) {
            System.err.println("[noturne] vertex shader failed: " + gl.shaderLog(vertex));
            gl.deleteShader(vertex);
            recordInitFailure();
            return false;
        }

        int fragment = gl.createShader(ModernGlApi.GL_FRAGMENT_SHADER);
        gl.shaderSource(fragment, FRAGMENT_SHADER);
        gl.compileShader(fragment);
        if (fragment == 0 || !gl.compileOk(fragment)) {
            System.err.println("[noturne] fragment shader failed: " + gl.shaderLog(fragment));
            gl.deleteShader(fragment);
            gl.deleteShader(vertex);
            recordInitFailure();
            return false;
        }

        int created = gl.createProgram();
        if (created == 0) {
            System.err.println("[noturne] glCreateProgram failed");
            gl.deleteShader(vertex);
            gl.deleteShader(fragment);
            recordInitFailure();
            return false;
        }
        program = created;
        gl.attachShader(program, vertex);
        gl.attachShader(program, fragment);
        // 顶点属性位置必须在链接前绑定：驱动默认可以把它分配到 0 以外的位置，
        // 而下面的 glVertexAttribPointer 固定按索引 0 描述布局。绑定句柄缺失
        // 即按初始化失败处理（P5），不能带着错乱的位置继续。
        if (!gl.bindAttribLocation(program, ATTRIB_POSITION, "aPos")) {
            System.err.println("[noturne] glBindAttribLocation unavailable; attribute layout uncertain");
            gl.deleteProgram(program);
            program = 0;
            gl.deleteShader(vertex);
            gl.deleteShader(fragment);
            recordInitFailure();
            return false;
        }
        // 链接完成后着色器对象即可删除，程序会保留各自的副本。
        gl.deleteShader(vertex);
        gl.deleteShader(fragment);
        gl.linkProgram(program);
        if (!gl.linkOk(program)) {
            System.err.println("[noturne] program link failed: " + gl.programLog(program));
            gl.deleteProgram(program);
            program = 0;
            recordInitFailure();
            return false;
        }

        projectionLocation = gl.uniformLocation(program, "uProjection");
        colorLocation = gl.uniformLocation(program, "uColor");
        if (projectionLocation < 0 || colorLocation < 0) {
            System.err.println("[noturne] shader uniforms missing: uProjection=" + projectionLocation
                    + " uColor=" + colorLocation);
            gl.deleteProgram(program);
            program = 0;
            recordInitFailure();
            return false;
        }

        vertexArray = gl.genVertexArray();
        vertexBuffer = gl.genBuffer();
        if (vertexArray == 0 || vertexBuffer == 0) {
            // genBuffer 曾因调用签名笔误恒返回 0，随后 glBindBuffer(0)/glBufferData 变成
            // 对空目标的操作，每帧 drawArrays 结果未定义却仍报 ready。这里直接判定未就绪。
            System.err.println("[noturne] buffer object creation failed: vao=" + vertexArray
                    + " vbo=" + vertexBuffer);
            gl.deleteProgram(program);
            program = 0;
            gl.deleteBuffer(vertexBuffer);
            vertexBuffer = 0;
            gl.deleteVertexArray(vertexArray);
            vertexArray = 0;
            recordInitFailure();
            return false;
        }
        gl.bindVertexArray(vertexArray);
        gl.bindArrayBuffer(vertexBuffer);
        gl.enableVertexAttrib(ATTRIB_POSITION);
        gl.vertexAttribPointer(ATTRIB_POSITION, 2, 8, 0);

        ready = true;
        return true;
    }

    /**
     * 设置用于构建投影矩阵的视口尺寸；窗口尺寸变化时调用。
     *
     * @param width  视口宽度（像素）
     * @param height 视口高度（像素）
     */
    public void setViewport(int width, int height) {
        setViewport(0, 0, width, height);
    }

    /**
     * 设置视口尺寸与原点；非全屏视口下必须传入原点，否则整个 GUI 会整体偏移。
     *
     * @param x      视口原点 x（像素）
     * @param y      视口原点 y（像素）
     * @param width  视口宽度（像素）
     * @param height 视口高度（像素）
     */
    public void setViewport(int x, int y, int width, int height) {
        this.screenX = x;
        this.screenY = y;
        this.screenWidth = width;
        this.screenHeight = height;
        this.projection = orthographic(x, y, width, height);
    }

    /** 释放本后端创建的 GL 对象；后端被替换或卸载时调用。 */
    public void dispose() {
        if (program != 0) {
            gl.deleteProgram(program);
            program = 0;
        }
        if (vertexBuffer != 0) {
            gl.deleteBuffer(vertexBuffer);
            vertexBuffer = 0;
        }
        if (vertexArray != 0) {
            gl.deleteVertexArray(vertexArray);
            vertexArray = 0;
        }
        ready = false;
    }

    @Override
    public String backendName() {
        return "gl-core";
    }

    @Override
    public int width() {
        return screenWidth;
    }

    @Override
    public int height() {
        return screenHeight;
    }

    /** @return 后端是否已成功初始化并处于可绘制状态 */
    @Override
    public boolean ready() {
        return ready || (canRetry() && initialise());
    }

    /** @return 尚未达到重试上限且当前已过退避时间 */
    private boolean canRetry() {
        return initFailures < MAX_INIT_FAILURES && System.nanoTime() >= nextRetryNanos;
    }

    /** 记录一次初始化失败，按指数退避推迟下一次重试（上限 {@value #MAX_INIT_FAILURES} 次）。 */
    private void recordInitFailure() {
        initFailures++;
        nextRetryNanos = System.nanoTime() + Math.min(4_000_000_000L,
                (1L << Math.min(initFailures, 10)) * 250_000_000L);
    }
    /** 应用每帧的 GL 状态；在绘制 GUI 之前调用。 */
    @Override
    public void beginFrame() {
        if (stateSaved) {
            // 上一帧的 endFrame 未执行：先还原，避免开关状态与投影逐帧累积。
            endFrame();
        }
        if (!ready && !initialise()) {
            return;
        }
        syncViewport();
        saveState();
        gl.useProgram(program);
        gl.bindVertexArray(vertexArray);
        blendBefore = gl.enableBlendAndReadPrevious();
        gl.disableDepthTest();
        // 投影含 -2/h 的 Y 翻转，所有三角形按绕序都是「背面」；不关剔除整个 GUI 会被剔光。
        gl.disableCullFace();
    }

    /**
     * 跟踪窗口尺寸与原点，使窗口缩放、切换全屏或使用非全屏视口后投影矩阵依然正确。
     */
    private void syncViewport() {
        int[] viewport = gl.getInteger(ModernGlApi.GL_VIEWPORT, 4);
        if (viewport == null || viewport[2] <= 0 || viewport[3] <= 0) {
            // GL 查询在该线程/上下文不可用（如 1.16.5 的 Render thread 上 glGetIntegerv
            // 调了不写值）：改走游戏自己的 Window 对象读帧缓冲尺寸（查表，无版本分支）。
            viewport = windowFramebuffer();
        }
        if (viewport == null) {
            return;
        }
        if (viewport[2] > 0 && viewport[3] > 0
                && (viewport[0] != screenX || viewport[1] != screenY
                || viewport[2] != screenWidth || viewport[3] != screenHeight)) {
            setViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    /**
     * 经游戏桥读 {@code Minecraft.getWindow()} 的帧缓冲宽高。
     *
     * <p>GL 视口查询不可用时的回退：1.13+ 的窗口尺寸游戏自己存了一份（每帧经
     * {@code glfwGetFramebufferSize} 刷新），走映射表读它，不写版本分支。
     * 1.8.9 等 LWJGL2 版本没有 Window 类（表里 absent），此时返回 {@code null}。
     *
     * @return {@code {0, 0, 宽, 高}}；读不到时为 {@code null}
     */
    private int[] windowFramebuffer() {
        if (bridge == null) {
            return null;
        }
        try {
            Object minecraft = bridge.minecraft();
            if (minecraft == null) {
                return null;
            }
            Object window = bridge.callMapped(minecraft,
                    dev.noturne.client.mapping.ClassType.MINECRAFT, "getWindow");
            if (window == null) {
                return null;
            }
            Object w = bridge.callMapped(window,
                    dev.noturne.client.mapping.ClassType.WINDOW, "getWidth");
            Object h = bridge.callMapped(window,
                    dev.noturne.client.mapping.ClassType.WINDOW, "getHeight");
            if (w instanceof Number && h instanceof Number
                    && ((Number) w).intValue() > 0 && ((Number) h).intValue() > 0) {
                return new int[]{0, 0, ((Number) w).intValue(), ((Number) h).intValue()};
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 记录被本后端改写的开关状态，供 {@link #endFrame()} 精确还原。 */
    private void saveState() {
        if (stateSaved || !gl.hasIsEnabled()) {
            return;
        }
        blendWasEnabled = gl.capabilityEnabled(ModernGlApi.GL_BLEND);
        depthWasEnabled = gl.capabilityEnabled(ModernGlApi.GL_DEPTH_TEST);
        cullWasEnabled = gl.capabilityEnabled(ModernGlApi.GL_CULL_FACE);
        stateSaved = true;
    }

    @Override
    public void endFrame() {
        if (stateSaved) {
            // 不做「无条件关混合」之类的粗暴清理：那会把同帧后续的游戏 HUD / 粒子绘制一并污染。
            restore(ModernGlApi.GL_BLEND, blendWasEnabled);
            restore(ModernGlApi.GL_DEPTH_TEST, depthWasEnabled);
            restore(ModernGlApi.GL_CULL_FACE, cullWasEnabled);
            // 还原混合因子：修复前只还原开关位，glBlendFunc 的 src/dst 因子永远留在我们的
            // src-alpha 值上，同帧后续的游戏 pass 若用别的因子（发光/加色/粒子）会被静默改写。
            gl.restoreBlendFunc(blendBefore);
            blendBefore = null;
            stateSaved = false;
        }
        if (clipDepth > 0) {
            gl.disableScissorTest();
            clipDepth = 0;
        }
        // 解绑 program/VAO/array buffer：1.13+ 的 GlStateManager 缓存这些绑定；不还原会让
        // 后续游戏代码「绑定自己的 VAO」被判为已绑定而跳过真实调用 → 画面损坏或崩溃。
        if (ready) {
            gl.bindVertexArray(0);
            gl.bindArrayBuffer(0);
            gl.useProgram(0);
        }
    }

    /** 还原单个开关位。 */
    private void restore(int cap, boolean enabled) {
        if (enabled) {
            gl.enableCap(cap);
        } else {
            gl.disableCap(cap);
        }
    }

    // -------------------------------------------------------------- Renderer 接口实现

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        if (width <= 0f || height <= 0f || color == null || !ready) {
            return;
        }
        ensureCapacity(12);
        int i = 0;
        // 两个三角形：(x,y) (x+w,y) (x+w,y+h) / (x,y) (x+w,y+h) (x,y+h)
        scratch[i++] = x;
        scratch[i++] = y;
        scratch[i++] = x + width;
        scratch[i++] = y;
        scratch[i++] = x + width;
        scratch[i++] = y + height;
        scratch[i++] = x;
        scratch[i++] = y;
        scratch[i++] = x + width;
        scratch[i++] = y + height;
        scratch[i++] = x;
        scratch[i++] = y + height;
        draw(scratch, i, color);
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        if (width <= 0f || height <= 0f || color == null || !ready) {
            return;
        }
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r <= 0.5f) {
            rect(x, y, width, height, color);
            return;
        }
        // 中心十字：横向一条 + 左右两条
        rect(x + r, y, width - 2f * r, height, color);
        rect(x, y + r, r, height - 2f * r, color);
        rect(x + width - r, y + r, r, height - 2f * r, color);
        // 四个角：每段的三角扇在核心 profile 下展开成三角形列表，坐标已在屏幕空间
        corner(x + r, y + r, r, 180f, 270f, color);
        corner(x + width - r, y + r, r, 270f, 360f, color);
        corner(x + width - r, y + height - r, r, 0f, 90f, color);
        corner(x + r, y + height - r, r, 90f, 180f, color);
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        if (width <= 0f || height <= 0f || color == null || !ready) {
            return;
        }
        // P9：线宽超过半高时上下两条在中部重叠，重叠区被画两遍（双混变实）；
        // 先夹到半高，退化成实心条而非重叠描边。
        float t = Math.max(1f, Math.min(lineWidth, Math.min(width, height) / 2f));
        rect(x, y, width, t, color);
        rect(x, y + height - t, width, t, color);
        rect(x, y + t, t, height - 2f * t, color);
        rect(x + width - t, y + t, t, height - 2f * t, color);
    }

    @Override
    public void text(String value, float x, float y, float size, Color color) {
        if (value == null || value.isEmpty()) {
            return;
        }
        // 交给游戏字体前必须让出本后端占用的 program 与 VAO：字体渲染器走自己的管线，
        // 留着我们的绑定会与它的状态互相覆盖。注意不要在这里改混合开关——
        // 字体绘制结束后不会替我们恢复，状态会与本后端的假设不符。
        if (ready) {
            gl.useProgram(0);
            gl.bindVertexArray(0);
        }
        text.draw(value, x, y, size, color);
        if (ready) {
            gl.useProgram(program);
            gl.bindVertexArray(vertexArray);
        }
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
            // 记账以保证与 popClip 配对；更深层不再改变 GL 状态。
            if (!clipOverflowWarned) {
                clipOverflowWarned = true;
                System.err.println("[noturne] core clip stack overflow (depth " + clipDepth
                        + " >= " + MAX_CLIP_DEPTH + "); deeper clips ignored");
            }
            clipDepth++;
            return;
        }
        int[] box = toScissorBox(x, y, width, height);
        if (clipDepth > 0) {
            box = intersection(clipStack[clipDepth - 1], box);
        }
        clipStack[clipDepth++] = box;
        gl.scissor(box[0], box[1], box[2], box[3]);
        gl.enableScissorTest();
    }

    @Override
    public void popClip() {
        if (clipDepth == 0) {
            return;
        }
        clipDepth--;
        if (clipDepth == 0) {
            gl.disableScissorTest();
            return;
        }
        if (clipDepth < MAX_CLIP_DEPTH) {
            int[] box = clipStack[clipDepth - 1];
            gl.scissor(box[0], box[1], box[2], box[3]);
        }
    }

    // -------------------------------------------------------------- 内部实现

    /**
     * 把屏幕坐标下的裁剪矩形换算成 {@code glScissor} 参数（窗口像素、原点左下）。
     *
     * <p>屏幕坐标是相对视口左上角的，需要叠加视口原点 {@link #screenX}/{@link #screenY}，
     * 再翻转 y 轴。与视口求交，越界部分不会把内容移进画面。
     */
    private int[] toScissorBox(float x, float y, float width, float height) {
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = x0 + Math.max(0, Math.round(width));
        int y1 = y0 + Math.max(0, Math.round(height));
        int vw = screenWidth > 0 ? screenWidth : Math.max(1, x1);
        int vh = screenHeight > 0 ? screenHeight : Math.max(1, y1);
        x0 = clamp(x0, 0, vw);
        x1 = clamp(x1, 0, vw);
        y0 = clamp(y0, 0, vh);
        y1 = clamp(y1, 0, vh);
        return new int[]{screenX + x0, screenY + vh - y1, x1 - x0, y1 - y0};
    }

    /** 把 {@code value} 夹取到 {@code [lo, hi]}。 */
    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
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
     * 绘制一个圆角扇形（核心 profile 无 {@code GL_TRIANGLE_FAN}，故展开成三角形列表）。
     *
     * @param cx,cy    圆心
     * @param radius   半径
     * @param startDeg 起始角度（度）
     * @param endDeg   结束角度（度）
     * @param color    平涂颜色
     */
    private void corner(float cx, float cy, float radius, float startDeg, float endDeg, Color color) {
        ensureCapacity((CORNER_SEGMENTS + 1) * 6);
        int i = 0;
        for (int segment = 0; segment < CORNER_SEGMENTS; segment++) {
            double a0 = Math.toRadians(startDeg + (endDeg - startDeg) * segment / (double) CORNER_SEGMENTS);
            double a1 = Math.toRadians(startDeg + (endDeg - startDeg) * (segment + 1) / (double) CORNER_SEGMENTS);
            float x0 = cx + (float) Math.cos(a0) * radius;
            float y0 = cy + (float) Math.sin(a0) * radius;
            float x1 = cx + (float) Math.cos(a1) * radius;
            float y1 = cy + (float) Math.sin(a1) * radius;
            // 三角形：圆心、p0、p1
            scratch[i++] = cx;
            scratch[i++] = cy;
            scratch[i++] = x0;
            scratch[i++] = y0;
            scratch[i++] = x1;
            scratch[i++] = y1;
        }
        draw(scratch, i, color);
    }

    /** 按需扩容暂存顶点缓冲；容量翻倍以免频繁重分配。 */
    private void ensureCapacity(int floats) {
        if (scratch.length < floats) {
            scratch = new float[Math.max(floats, scratch.length * 2)];
        }
    }

    /**
     * 上传暂存顶点并绘制。
     *
     * <p>只上传恰好用到的前 {@code length} 个元素（{@code uploadArrayBuffer} 的定长重载），
     * 因此无需像旧实现那样每次绘制都 {@code new float[length]} 复制一份，避免逐帧短命数组。
     */
    private void draw(float[] data, int length, Color color) {
        int vertices = length / 2;
        if (vertices == 0) {
            return;
        }
        gl.bindArrayBuffer(vertexBuffer);
        gl.uploadArrayBuffer(data, length);
        gl.uniformMatrix4fv(projectionLocation, projection);
        gl.uniform4f(colorLocation, color.rf(), color.gf(), color.bf(), color.af());
        gl.drawTriangles(0, vertices);
    }

    /**
     * 列主序正交投影矩阵，把视口左上角 (x,y) 映射到 NDC 的 (-1,1)，
     * 使 UI 可以直接使用相对视口的屏幕像素坐标。
     */
    static float[] orthographic(int width, int height) {
        return orthographic(0, 0, width, height);
    }

    /**
     * 带视口原点的列主序正交投影矩阵。
     *
     * @param x,y      视口在窗口中的原点（像素）
     * @param width,h  视口宽高（像素）
     */
    static float[] orthographic(int x, int y, int width, int height) {
        float w = width <= 0 ? 1f : width;
        float h = height <= 0 ? 1f : height;
        return new float[]{
                2f / w, 0f, 0f, 0f,
                0f, -2f / h, 0f, 0f,
                0f, 0f, -1f, 0f,
                -2f * x / w - 1f, 2f * y / h + 1f, 0f, 1f,
        };
    }
}
