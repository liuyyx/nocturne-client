package dev.noturne.ui.gl;

import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.skija.SkijaUi;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.types.Rect;

/**
 * {@link Renderer} 的 Skija 实现：把组件树画到 {@link SkijaCanvas} 给出的画布上。
 *
 * <p>与 GL 实现的关系：{@code GlRenderer}/{@code ModernRenderer} 是按「游戏用哪代 OpenGL」分的，
 * 本类**不分代**——Skia 只要拿到当前 GL 上下文就能画，因此同一份组件代码在 1.8.9 与 26.x 上行为一致。
 *
 * <p>绘制原语与字体栈全部委托 {@link SkijaUi}（自 Setsuna 移植，GPL-3.0-or-later，见
 * THIRD-PARTY-NOTICES.md）：本类只做接口适配与边界防护，不再自己管理画笔与字体缓存——两套字体
 * 度量并存会让测量宽度与实际绘制对不上（文本居中、省略号都会错位）。
 */
public final class SkijaRenderer implements Renderer {

    /** 当前帧的画布；由 {@link #bind(Canvas)} 设置，为空时所有绘制都是空操作。 */
    private Canvas canvas;

    /** 裁剪是否开启（对应 {@link #pushClip}/{@link #popClip} 的配对）。 */
    private int clipDepth;

    /**
     * 绑定本帧画布。
     *
     * @param canvas Skija 画布；为 {@code null} 表示本帧不可绘制（所有调用退化为空操作）
     */
    public void bind(Canvas canvas) {
        this.canvas = canvas;
        this.clipDepth = 0;
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        if (!drawable(width, height, color)) {
            return;
        }
        SkijaUi.fill(canvas, x, y, width, height, color.packed());
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        if (!drawable(width, height, color)) {
            return;
        }
        if (radius <= 0.5f) {
            SkijaUi.fill(canvas, x, y, width, height, color.packed());
            return;
        }
        SkijaUi.rounded(canvas, x, y, width, height, radius, color.packed());
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        if (!drawable(width, height, color) || lineWidth <= 0f) {
            return;
        }
        // 描边以线宽为半径居中在边界上：内缩半个线宽，视觉上才与填充矩形对齐。
        float inset = lineWidth / 2f;
        float radius = Math.min(3f, Math.min(width, height) / 4f);
        SkijaUi.outline(canvas, x + inset, y + inset,
                Math.max(0f, width - lineWidth), Math.max(0f, height - lineWidth),
                radius, lineWidth, color.packed());
    }

    @Override
    public void text(String text, float x, float y, float size, Color color) {
        if (canvas == null || text == null || text.isEmpty() || color == null || color.a() == 0) {
            return;
        }
        // SkijaUi 的 text 以「行盒」定位：给定顶边与行盒高度，内部按 ascent/descent 垂直居中。
        // 这里行盒高度取字号本身，与组件树的坐标约定一致。
        SkijaUi.text(canvas, text, x, y, size, color.packed(), size);
    }

    @Override
    public float textWidth(String text, float size) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        // 走 SkijaUi 的度量：它带 CJK 回退——同一个字符串在「测量」与「绘制」上用同一套字体选择，
        // 否则中文段的宽度会与预期不符。
        return SkijaUi.textWidth(text, size);
    }

    @Override
    public float textHeight(float size) {
        return SkijaUi.lineHeight(size);
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        if (canvas == null || width <= 0f || height <= 0f) {
            return;
        }
        canvas.save();
        canvas.clipRect(Rect.makeXYWH(x, y, width, height), ClipMode.INTERSECT);
        clipDepth++;
    }

    @Override
    public void popClip() {
        if (canvas == null || clipDepth <= 0) {
            return;
        }
        canvas.restore();
        clipDepth--;
    }

    /** 本帧可绘制的统一前置条件：有画布、颜色有效、尺寸为正、不透明。 */
    private boolean drawable(float width, float height, Color color) {
        return canvas != null && color != null && width > 0f && height > 0f && color.a() != 0;
    }
}
