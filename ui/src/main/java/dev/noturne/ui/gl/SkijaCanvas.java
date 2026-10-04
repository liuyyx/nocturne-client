package dev.noturne.ui.gl;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;

/**
 * Skija 画布：把 Skia 挂到**当前 GL 上下文**上，并把界面画进**游戏帧缓冲**。
 *
 * <p>这是「一份绘制代码管所有版本」的关键：不依赖游戏的绘制 API（1.8.9 的固定管线、1.16+ 的
 * {@code DrawContext}/{@code GuiGraphics}、26.x 的 {@code GuiGraphicsExtractor} 一律不管），
 * 也不依赖游戏字体——Skija 自带字体栈。已实测：Java 8 + LWJGL2 + 真实 GL 上下文里可正常绘制。
 *
 * <p>生命周期：{@link #beginFrame(int, int)} 确保上下文与目标尺寸的表面就绪并返回画布，
 * 绘制完调用 {@link #endFrame()} 提交；尺寸变化时自动重建表面。任一步失败都会置为
 * {@link #ready()} 为 false 并**打印一次**原因，绝不每帧刷屏，也绝不抛给帧线程。
 *
 * <p>已知代价：Skija 会把原生库解压到临时目录（`skija.dll` 等），这是它自带 loader 的行为。
 */
public final class SkijaCanvas {

    /** 是否已打印过失败诊断（限流）。 */
    private boolean reported;
    /** 原生库或上下文不可用；置位后不再重试，避免每帧抛异常。 */
    private boolean broken;

    /** Skia 的 GL 上下文；首次绘制时创建。 */
    private DirectContext context;
    /** 当前帧缓冲对应的绘制表面。 */
    private Surface surface;
    /** 表面尺寸；与请求尺寸不一致时重建。 */
    private int surfaceWidth;
    private int surfaceHeight;

    /**
     * 开始一帧：确保 Skia 上下文与目标尺寸的表面可用。
     *
     * @param width  绘制区域宽度（像素），来自 GL 视口
     * @param height 绘制区域高度（像素）
     * @return 可绘制的画布；不可用时返回 {@code null}（调用方应跳过绘制）
     */
    public Canvas beginFrame(int width, int height) {
        if (broken || width <= 0 || height <= 0) {
            return null;
        }
        try {
            if (context == null) {
                context = DirectContext.makeGL();
                if (context == null) {
                    fail("DirectContext.makeGL() returned null");
                    return null;
                }
            }
            if (surface == null || surfaceWidth != width || surfaceHeight != height) {
                closeSurface();
                // framebuffer 0 = 当前绑定的帧缓冲（游戏自己画完就是要翻页的那张），
                // 因此我们画上去的东西会直接被交换到屏幕上。
                BackendRenderTarget target = BackendRenderTarget.makeGL(
                        width, height, 0, 8, 0, FramebufferFormat.GR_GL_RGBA8);
                surface = Surface.makeFromBackendRenderTarget(
                        context, target, SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888,
                        ColorSpace.getSRGB(), null);
                if (surface == null) {
                    fail("Surface.makeFromBackendRenderTarget() returned null");
                    return null;
                }
                surfaceWidth = width;
                surfaceHeight = height;
                report("skija surface " + width + "x" + height + " ready");
            }
            return surface.getCanvas();
        } catch (Throwable t) {
            fail("skija beginFrame failed: " + t);
            return null;
        }
    }

    /**
     * Skia 的 GL 上下文；尚未绘制过时为 {@code null}。
     *
     * <p>纹理借用（{@code SkijaTextureBridge}）需要它：借来的 GL 纹理只在同一个上下文里有效。
     */
    public DirectContext context() {
        return context;
    }

    /**
     * 当前绘制表面；尚未开始绘制时为 {@code null}。
     *
     * <p>背景模糊要在绘制 UI **之前**用它抓一份快照，否则会把已经画上去的 UI 一起模糊掉。
     */
    public Surface surface() {
        return surface;
    }

    /**
     * 提交本帧绘制。
     *
     * <p>{@code flushAndSubmit(false)}：只提交 GPU 命令、不同步等待，避免把帧线程卡住。
     */
    public void endFrame() {
        if (context == null || surface == null) {
            return;
        }
        try {
            context.flushAndSubmit(false);
        } catch (Throwable t) {
            fail("skija endFrame failed: " + t);
        }
    }

    /** 尺寸变化或收回时释放表面（上下文保留，重建很便宜）。 */
    private void closeSurface() {
        if (surface != null) {
            try {
                surface.close();
            } catch (Throwable ignored) {
                // 释放失败不影响后续重建
            }
            surface = null;
        }
    }

    /** 当前是否可用（未因原生库/上下文问题被标记为损坏）。 */
    public boolean ready() {
        return !broken;
    }

    /** 记录首次失败并停止重试。 */
    private void fail(String message) {
        broken = true;
        closeSurface();
        report(message);
    }

    /** 一次性诊断输出（成功与失败都只打印一次）。 */
    private void report(String message) {
        if (!reported) {
            reported = true;
            System.out.println("[noturne] " + message);
        }
    }
}
