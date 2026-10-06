package dev.nocturne.ui.gl;

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
    /** 建 surface 用的后端目标（P2：原生资源，随 surface 一起释放，否则泄漏）。 */
    private BackendRenderTarget target;
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
            } else {
                // 游戏每帧都会改 Skia 依赖的 GL 状态（纹理单元、解包对齐等），而 Skia
                // 只在首帧同步一次：不重置的话，HUD 的几何与文字会丢失（GUI 恰好因后画、
                // 状态被游戏下一轮改回而幸免）。每次绘制前强制重置，让 Skia 按当前
                // 实际 GL 状态重建管线——这是 HUD 白字/几何丢失的根因修复。
                context.resetGLAll();
            }
            if (surface == null || surfaceWidth != width || surfaceHeight != height) {
                closeSurface();
                // framebuffer 0 = 当前绑定的帧缓冲（游戏自己画完就是要翻页的那张），
                // 因此我们画上去的东西会直接被交换到屏幕上。
                // 必须用 wrap 而不是 make：make 会让 Skia 接管该 RT 并 discard 原有像素
                // （游戏画好的内容被丢掉 → 游戏全黑）；wrap 只包装已有内容，原像素保留。
                BackendRenderTarget fresh = BackendRenderTarget.makeGL(
                        width, height, 0, 8, 0, FramebufferFormat.GR_GL_RGBA8);
                surface = Surface.wrapBackendRenderTarget(
                        context, fresh, SurfaceOrigin.BOTTOM_LEFT, ColorType.RGBA_8888,
                        ColorSpace.getSRGB());
                if (surface == null) {
                    fail("Surface.makeFromBackendRenderTarget() returned null");
                    return null;
                }
                target = fresh;
                surfaceWidth = width;
                surfaceHeight = height;
                report("skija surface " + width + "x" + height + " ready");
            }
            return surface.getCanvas();
        } catch (Throwable t) {
            fail("skija beginFrame failed: " + t);
            // 只打异常消息会丢掉「到底是谁引用了缺失的类」这一关键信息（实测：JDK 9+ 没有
            // sun.misc.Cleaner，但仅凭消息无法判断引用方），因此这里补一次完整堆栈。
            t.printStackTrace();
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
            // 只 flush 不 submit：submit 会触发 Skia 对外部帧缓冲的呈现动作（discard/resolve），
            // 把游戏画好的内容盖黑；flush 只提交绘制命令，overlay 照常上屏。
            context.flush();
        } catch (Throwable t) {
            fail("skija endFrame failed: " + t);
        } finally {
            // 帧缓冲绑定归还（Skia 可能切到中间目标）；1.8.9 默认目标恒为 0。
            restoreDefaultFramebuffer();
        }
    }

    /** 把帧缓冲绑定归还给 0（游戏默认目标）；反射走 LWJGL，失败静默（下帧游戏自会重绑）。 */
    private static void restoreDefaultFramebuffer() {
        try {
            try {
                Class<?> gl30 = Class.forName("org.lwjgl.opengl.GL30");
                gl30.getMethod("glBindFramebuffer", int.class, int.class)
                        .invoke(null, Integer.valueOf(0x8D40), Integer.valueOf(0));
                return;
            } catch (Throwable ignored) {
                // 无 GL30（老上下文）时回退 EXT 分支。
            }
            Class<?> ext = Class.forName("org.lwjgl.opengl.EXTFramebufferObject");
            ext.getMethod("glBindFramebufferEXT", int.class, int.class)
                    .invoke(null, Integer.valueOf(0x8D40), Integer.valueOf(0));
        } catch (Throwable ignored) {
            // 两种入口都没有：不动，让游戏自己恢复。
        }
    }

    /** 尺寸变化或收回时释放表面与后端目标（上下文保留，重建很便宜）。 */
    private void closeSurface() {
        if (surface != null) {
            try {
                surface.close();
            } catch (Throwable ignored) {
                // 释放失败不影响后续重建
            }
            surface = null;
        }
        if (target != null) {
            try {
                target.close();
            } catch (Throwable ignored) {
                // 释放失败不影响后续重建
            }
            target = null;
        }
        surfaceWidth = 0;
        surfaceHeight = 0;
    }

    /**
     * 释放全部原生资源（表面 + 上下文），并标记为不可用。
     *
     * <p>探测失败（D18）时调用：DirectContext 与 Surface 持有 GPU 资源，不关就泄漏；
     * 全仓之前没有任何 close 入口。
     */
    public void close() {
        closeSurface();
        if (context != null) {
            try {
                context.close();
            } catch (Throwable ignored) {
                // 释放失败不影响标记不可用
            }
            context = null;
        }
        broken = true;
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
            System.out.println("[nocturne] " + message);
        }
    }
}
