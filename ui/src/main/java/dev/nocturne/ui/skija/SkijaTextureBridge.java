/*
 * 纹理借用与帧快照部分移植自 Setsuna 的 render/SkijaRenderer.java（上游 commit
 * e4915ae093748d48d92ee47c38cdb8b2746a4730，作者 ShiYi，许可 GPL-3.0-or-later，
 * 见 THIRD-PARTY-NOTICES.md）。
 * 本文件相对上游的改动：抽出「纹理 → Skia 图像」这一段，去掉 Minecraft 依赖
 * （上游直接读 Minecraft 的 TextureManager 与 AbstractTexture；这里只接受 GL 纹理 id 与尺寸，
 * 于是本类可以在没有游戏的进程里被完整验证）；快照改用 Skija 自身的 Surface.makeImageSnapshot，
 * 不再依赖 MC 帧缓冲读数。
 */
package dev.nocturne.ui.skija;

import io.github.humbleui.skija.BackendTexture;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.GLTextureInfo;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;

/**
 * 纹理桥：把**已上传的 GL 纹理**与**当前帧内容**变成 Skia 可以绘制的 {@link Image}。
 *
 * <p>为什么需要它：HUD 与 GUI 里有一部分内容来自游戏的纹理（玩家皮肤头像、物品图标、旗帜），
 * 它们在游戏里只是 GL 纹理对象；而 Skija 只能画自己的 {@link Image}。桥的职责就是"借"——不是
 * 拷贝像素，而是让 Skia 直接引用那块 GPU 纹理。
 *
 * <p>刻意**不依赖 Minecraft**：上游直接读 {@code TextureManager} 与 {@code AbstractTexture}，
 * 那样本类只能在有游戏时验证。这里只接受「GL 纹理 id + 尺寸」，取 id 的事留给调用方（用映射层
 * 读 MC 的字段）——于是本类在没有游戏的进程里也能被完整测到。
 *
 * <p>三个必须守住的约束：
 * <ul>
 *   <li><b>生命周期</b>：借来的图像在同一帧内有效，用完必须 {@link Borrowed#close()}，否则
 *       Skia 侧的原生对象会持续累积（每帧一次就是稳定泄漏）。</li>
 *   <li><b>同一上下文</b>：GL 纹理只在创建它的 GL 上下文里有效，因此上下文必须来自当前正在
 *       绘制的那个 Skija 画布（{@code SkijaCanvas.context()}）。</li>
 *   <li><b>快照时机</b>：背景模糊的快照必须在画 UI **之前**抓，否则会把已经画上去的界面一起
 *       模糊，形成递归采样。</li>
 * </ul>
 */
public final class SkijaTextureBridge {

    /** GL_TEXTURE_2D。硬编码而不引 LWJGL：本模块编译期不依赖任何一代 LWJGL 常量表。 */
    private static final int GL_TEXTURE_2D = 0x0DE1;
    /** GL_RGBA8。 */
    private static final int GL_RGBA8 = 0x8058;

    private SkijaTextureBridge() {
    }

    /**
     * 借来的纹理：{@link Image} 与它背后的 backend 必须一起释放。
     *
     * <p>不要只关其中一个：只关 Image 会留下 backend 句柄，只关 backend 会让 Image 指向已失效的纹理。
     */
    public static final class Borrowed implements AutoCloseable {

        private final Image image;
        private final BackendTexture backend;
        private boolean closed;

        private Borrowed(Image image, BackendTexture backend) {
            this.image = image;
            this.backend = backend;
        }

        /** @return 可绘制的图像 */
        public Image image() {
            return image;
        }

        /** 幂等：重复关闭不会二次释放原生对象。 */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                image.close();
            } catch (Throwable ignored) {
                // 释放失败不改变调用方语义：本帧结束后该图像不再被使用
            }
            try {
                backend.close();
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    /**
     * 借用一个已上传的 RGBA8 二维纹理。
     *
     * @param context     Skija 的 GL 上下文（须为当前绘制所用的那个）
     * @param glTextureId GL 纹理名
     * @param width       纹理宽度（像素）
     * @param height      纹理高度（像素）
     * @return 借来的图像；任一前提不成立或 Skija 拒绝时返回 {@code null}（调用方跳过绘制即可）
     */
    public static Borrowed borrowGlTexture(DirectContext context, int glTextureId, int width,
                                           int height) {
        if (context == null || glTextureId <= 0 || width <= 0 || height <= 0) {
            return null;
        }
        BackendTexture backend = null;
        try {
            backend = BackendTexture.makeGL(width, height, false,
                    new GLTextureInfo(GL_TEXTURE_2D, glTextureId, GL_RGBA8));
            Image image = Image.borrowTextureFrom(context, backend, SurfaceOrigin.TOP_LEFT,
                    ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.getSRGB(), null);
            if (image == null) {
                backend.close();
                return null;
            }
            return new Borrowed(image, backend);
        } catch (Throwable failure) {
            if (backend != null) {
                try {
                    backend.close();
                } catch (Throwable ignored) {
                    // 这里已经在处理失败路径，释放失败只影响句柄回收
                }
            }
            System.out.println("[nocturne] texture borrow failed (id=" + glTextureId + " "
                    + width + "x" + height + "): " + failure);
            return null;
        }
    }

    /**
     * 抓取当前表面的内容作为图像（背景模糊的源）。
     *
     * <p>调用时机是硬约束：必须在开始绘制 UI **之前**。否则快照里已经包含本帧要模糊的那层 UI，
     * 模糊结果会自我叠加（画面越糊越亮）。
     *
     * @param surface 当前绘制表面（{@code SkijaCanvas.surface()}）
     * @return 快照图像；不可用时返回 {@code null}
     */
    public static Image snapshot(Surface surface) {
        if (surface == null) {
            return null;
        }
        try {
            return surface.makeImageSnapshot();
        } catch (Throwable failure) {
            System.out.println("[nocturne] framebuffer snapshot failed: " + failure);
            return null;
        }
    }
}
