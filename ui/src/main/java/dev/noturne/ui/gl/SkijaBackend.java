package dev.noturne.ui.gl;

import dev.noturne.ui.render.Color;

import io.github.humbleui.skija.Canvas;

/**
 * {@link UiBackend} 的 Skija 实现：每帧把 Skia 画布接到当前 GL 上下文上，收尾时提交。
 *
 * <p>与 {@link GlRenderer} / {@link ModernRenderer} 的区别：那两个按「游戏用哪代 OpenGL」分化，
 * 本后端**不分代**——只要能把 Skia 挂到当前上下文并把界面画进帧缓冲，同一份组件代码在
 * 1.8.9（固定管线）与 26.x（核心 profile + 抽取式 GUI 管线）上行为一致。
 *
 * <p>尺寸来自 GL 视口（与 GL 后端同源，经 {@link GlApi} 读取，已修好 LWJGL2 上视口的
 * {@code IntBuffer} 形态），因此 GUI 坐标与帧缓冲像素一致。
 */
public final class SkijaBackend implements UiBackend {

    /** 底层 GL 绑定，仅用于读取视口尺寸。 */
    private final GlApi gl;
    /** Skija 画布生命周期。 */
    private final SkijaCanvas skija = new SkijaCanvas();
    /** 实际绘制实现。 */
    private final SkijaRenderer renderer = new SkijaRenderer();

    /** 最近一次取得的绘制区域尺寸。 */
    private int viewportWidth;
    private int viewportHeight;
    /** 最近一帧是否真的拿到了画布（Skija 可用）。 */
    private boolean lastFrameUsable;
    /**
     * 本帧的画布；未开始绘制或 Skija 不可用时为 {@code null}。
     *
     * <p>供**Canvas 直绘界面**（如 {@code SetsunaClickGui}）取用：那类界面直接用 Skija 画
     * 玻璃层/模糊/图标字体，这些效果无法用 {@link dev.noturne.ui.render.Renderer} 的
     * 矩形+文字原语表达。
     */
    private Canvas frameCanvas;

    public SkijaBackend(GlApi gl) {
        this.gl = gl;
    }

    /**
     * 探测 Skija 是否真的可用：立刻走一帧（建上下文 + 建表面 + 提交）。
     *
     * <p>只用来在**安装叠加层时做一次**选择：可用就整场都用 Skija；不可用（例如当前平台
     * 没带原生库）就回落到按代际的 GL 后端，而不是每帧试探。
     *
     * @param gl 固定管线绑定（用于读视口尺寸；可为 {@code null}，此时取不到尺寸即判为不可用）
     * @return 可用的后端实例；不可用时返回 {@code null}
     */
    public static SkijaBackend probe(GlApi gl) {
        SkijaBackend backend = new SkijaBackend(gl);
        try {
            backend.beginFrame();
            boolean usable = backend.ready();
            backend.endFrame();
            if (usable) {
                System.out.println("[noturne] skija probe: usable (viewport=" + backend.width() + "x"
                        + backend.height() + ") mark=diag1");
                return backend;
            }
            // 静默返回 null 会让「为什么没用 Skija」完全无从下手：日志里既没有成功行、也没有失败行
            // （26.3 实机就撞上了这一点，只能靠代码推断）。这里把判定依据直接打出来。
            System.out.println("[noturne] skija probe: unusable (viewport=" + backend.width() + "x"
                    + backend.height() + ", canvas=" + (backend.canvas() != null)
                    + ", gl=" + (gl != null) + ")");
            return null;
        } catch (Throwable t) {
            System.out.println("[noturne] skija probe failed: " + t);
            return null;
        }
    }

    @Override
    public void beginFrame() {
        int[] viewport = gl == null ? null : gl.viewport();
        if (viewport != null && viewport[2] > 0 && viewport[3] > 0) {
            viewportWidth = viewport[2];
            viewportHeight = viewport[3];
        }
        Canvas canvas = skija.beginFrame(viewportWidth, viewportHeight);
        lastFrameUsable = canvas != null;
        frameCanvas = canvas;
        renderer.bind(canvas);
    }

    @Override
    public void endFrame() {
        skija.endFrame();
        renderer.bind(null);
        frameCanvas = null;
    }

    /**
     * 本帧的画布；当前不在 Skija 绘制期（或 Skija 不可用）时为 {@code null}。
     *
     * <p>仅在 {@link #beginFrame()} 与 {@link #endFrame()} 之间有效——Canvas 直绘界面必须在这段
     * 窗口内使用它，跨帧持有会指向已被提交/复用的原生表面。
     */
    public Canvas canvas() {
        return frameCanvas;
    }

    @Override
    public String backendName() {
        return "skija";
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
    public boolean ready() {
        return skija.ready() && lastFrameUsable;
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        renderer.rect(x, y, width, height, color);
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        renderer.roundedRect(x, y, width, height, radius, color);
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        renderer.outline(x, y, width, height, lineWidth, color);
    }

    @Override
    public void text(String text, float x, float y, float size, Color color) {
        renderer.text(text, x, y, size, color);
    }

    @Override
    public float textWidth(String text, float size) {
        return renderer.textWidth(text, size);
    }

    @Override
    public float textHeight(float size) {
        return renderer.textHeight(size);
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        renderer.pushClip(x, y, width, height);
    }

    @Override
    public void popClip() {
        renderer.popClip();
    }
}
