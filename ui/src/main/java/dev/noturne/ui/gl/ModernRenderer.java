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
 * <p>它是两个可互换后端之一，另见 {@code RenderBackends}。
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

    /** 每个 90° 圆角细分的段数；与固定管线后端保持一致，保证两条路径观感相同。 */
    private static final int CORNER_SEGMENTS = 6;

    /** 核心 profile 绑定；不得为 {@code null}。 */
    private final ModernGlApi gl;
    /** 文本渲染器；构造时 {@code null} 会被替换为 {@link TextRenderer#NONE}。 */
    private final TextRenderer text;

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
    /** 当前正交投影矩阵（16 个元素，列主序）。 */
    private float[] projection = new float[16];
    /** 缓存的窗口宽度，用于检测视口变化。 */
    private int screenWidth;
    /** 缓存的窗口高度，用于检测视口变化。 */
    private int screenHeight;

    /** 供每次绘制复用的暂存顶点缓冲，使整个渲染过程不产生逐帧分配。 */
    private float[] scratch = new float[256];

    /**
     * 构造渲染器。
     *
     * @param gl   核心 profile 绑定
     * @param text 文本渲染器，传 {@code null} 时退化为空实现
     */
    public ModernRenderer(ModernGlApi gl, TextRenderer text) {
        this.gl = gl;
        this.text = text == null ? TextRenderer.NONE : text;
    }

    /** @return 底层 GL 绑定，供调用方做低层设置或诊断 */
    public ModernGlApi gl() {
        return gl;
    }

    /**
     * 编译着色器并创建缓冲对象。必须已有当前 GL 上下文时调用，且只需调用一次。
     *
     * @return 是否初始化成功
     */
    public boolean initialise() {
        if (ready) {
            return true;
        }
        int vertex = gl.createShader(ModernGlApi.GL_VERTEX_SHADER);
        gl.shaderSource(vertex, VERTEX_SHADER);
        gl.compileShader(vertex);
        if (!gl.compileOk(vertex)) {
            System.err.println("[noturne] vertex shader failed: " + gl.shaderLog(vertex));
            return false;
        }
        int fragment = gl.createShader(ModernGlApi.GL_FRAGMENT_SHADER);
        gl.shaderSource(fragment, FRAGMENT_SHADER);
        gl.compileShader(fragment);
        if (!gl.compileOk(fragment)) {
            System.err.println("[noturne] fragment shader failed: " + gl.shaderLog(fragment));
            return false;
        }

        program = gl.createProgram();
        gl.attachShader(program, vertex);
        gl.attachShader(program, fragment);
        gl.linkProgram(program);
        projectionLocation = gl.uniformLocation(program, "uProjection");
        colorLocation = gl.uniformLocation(program, "uColor");

        vertexArray = gl.genVertexArray();
        vertexBuffer = gl.genBuffer();
        gl.bindVertexArray(vertexArray);
        gl.bindArrayBuffer(vertexBuffer);
        gl.enableVertexAttrib(0);
        gl.vertexAttribPointer(0, 2, 8, 0);

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
        this.screenWidth = width;
        this.screenHeight = height;
        this.projection = orthographic(width, height);
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

    /** 应用每帧的 GL 状态；在绘制 GUI 之前调用。 */
    @Override
    public void beginFrame() {
        if (!ready && !initialise()) {
            return;
        }
        syncViewport();
        gl.useProgram(program);
        gl.bindVertexArray(vertexArray);
        gl.enableBlend();
        gl.disableTexture();
    }

    /** 跟踪窗口尺寸，使窗口缩放或切换全屏后投影矩阵依然正确。 */
    private void syncViewport() {
        int[] viewport = gl.getInteger(ModernGlApi.GL_VIEWPORT, 4);
        if (viewport == null) {
            return;
        }
        if (viewport[2] > 0 && viewport[3] > 0
                && (viewport[2] != screenWidth || viewport[3] != screenHeight)) {
            setViewport(viewport[2], viewport[3]);
        }
    }

    @Override
    public void endFrame() {
        gl.disableBlend();
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
        float t = Math.max(1f, lineWidth);
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
        // 游戏自带的字体渲染器会自行发出 GL 调用，所以先释放本渲染器的批次状态。
        endFrame();
        text.draw(value, x, y, size, color);
        beginFrame();
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
        // 裁剪测试需要当前 FBO 的高度才能正确换算，暂时留给调用方处理。
    }

    @Override
    public void popClip() {
        // 见 pushClip：本后端尚未启用裁剪测试。
    }

    // -------------------------------------------------------------- 内部实现

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
     * <p>这里必须复制成恰好长度的数组——{@code uploadArrayBuffer} 会按数组长度分配直接缓冲区，
     * 直接传暂存数组会把上次残留的顶点一并上传。
     */
    private void draw(float[] data, int length, Color color) {
        int vertices = length / 2;
        if (vertices == 0) {
            return;
        }
        gl.bindArrayBuffer(vertexBuffer);
        float[] exact = new float[length];
        System.arraycopy(data, 0, exact, 0, length);
        gl.uploadArrayBuffer(exact);
        gl.uniformMatrix4fv(projectionLocation, projection);
        gl.uniform4f(colorLocation, color.rf(), color.gf(), color.bf(), color.af());
        gl.drawTriangles(0, vertices);
    }

    /** 列主序正交投影矩阵，把 (0,0) 映射到左上角，使 UI 可以直接使用屏幕像素坐标。 */
    static float[] orthographic(int width, int height) {
        float w = width <= 0 ? 1f : width;
        float h = height <= 0 ? 1f : height;
        return new float[]{
                2f / w, 0f, 0f, 0f,
                0f, -2f / h, 0f, 0f,
                0f, 0f, -1f, 0f,
                -1f, 1f, 0f, 1f,
        };
    }
}
