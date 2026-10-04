package dev.noturne.ui.gl;

import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link Renderer} 的 Skija 实现：把组件树画到 {@link SkijaCanvas} 给出的画布上。
 *
 * <p>与 GL 实现的关系：{@code GlRenderer}/{@code ModernRenderer} 是按「游戏用哪代 OpenGL」分的，
 * 本类**不分代**——Skia 只要拿到当前 GL 上下文就能画，因此同一份组件代码在 1.8.9 与 26.x 上行为一致。
 *
 * <p>字体自带：优先系统中文字体（界面文案是中文），失败则退回系统默认字体；字号→{@link Font} 有缓存，
 * 避免每帧新建原生对象。
 */
public final class SkijaRenderer implements Renderer {

    /** 系统中文字体候选（按顺序取第一个存在的）；都取不到时用 Skia 默认字体。 */
    private static final String[] TYPEFACE_CANDIDATES = {
            "Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", "Noto Sans CJK SC",
            "PingFang SC", "Source Han Sans SC", "WenQuanYi Micro Hei", "sans-serif",
    };

    /** 共享的字体管理器（Skia 全局唯一）。 */
    private static Typeface sharedTypeface;

    /** 当前帧的画布；由 {@link #bind(Canvas)} 设置，为空时所有绘制都是空操作。 */
    private Canvas canvas;

    /** 复用的填充/描边画笔，避免每帧分配原生对象。 */
    private final Paint fill = new Paint().setAntiAlias(true).setMode(PaintMode.FILL);
    private final Paint stroke = new Paint().setAntiAlias(true).setMode(PaintMode.STROKE);

    /** 字号 → 字体缓存。 */
    private final Map<Integer, Font> fonts = new HashMap<Integer, Font>();
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

    /** 取（必要时创建）指定字号的字体。 */
    private Font font(float size) {
        int key = Math.max(1, Math.round(size));
        Font cached = fonts.get(Integer.valueOf(key));
        if (cached != null) {
            return cached;
        }
        if (sharedTypeface == null) {
            sharedTypeface = pickTypeface();
        }
        Font created = sharedTypeface == null ? new Font() : new Font(sharedTypeface, key);
        fonts.put(Integer.valueOf(key), created);
        return created;
    }

    /** 挑一个支持中文的系统字体；取不到时返回 {@code null}（由 Skia 用默认字体）。 */
    private static Typeface pickTypeface() {
        FontMgr manager = FontMgr.getDefault();
        if (manager == null) {
            return null;
        }
        for (String family : TYPEFACE_CANDIDATES) {
            try {
                Typeface typeface = manager.matchFamilyStyle(family, FontStyle.NORMAL);
                if (typeface != null) {
                    System.out.println("[noturne] skija typeface: " + family);
                    return typeface;
                }
            } catch (Throwable ignored) {
                // 该字体不可用，继续试下一个
            }
        }
        return null;
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        if (canvas == null || color == null || width <= 0f || height <= 0f || color.a() == 0) {
            return;
        }
        fill.setColor(color.packed());
        canvas.drawRect(Rect.makeXYWH(x, y, width, height), fill);
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        if (canvas == null || color == null || width <= 0f || height <= 0f || color.a() == 0) {
            return;
        }
        if (radius <= 0.5f) {
            rect(x, y, width, height, color);
            return;
        }
        fill.setColor(color.packed());
        canvas.drawRRect(RRect.makeXYWH(x, y, width, height, radius), fill);
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        if (canvas == null || color == null || width <= 0f || height <= 0f || color.a() == 0) {
            return;
        }
        stroke.setColor(color.packed());
        // 描边以线宽为半径居中在边界上：内缩半个线宽，视觉上才与填充矩形对齐。
        float inset = Math.max(0f, lineWidth) / 2f;
        float radius = Math.min(3f, Math.min(width, height) / 4f);
        canvas.drawRRect(RRect.makeXYWH(x + inset, y + inset,
                Math.max(0f, width - lineWidth), Math.max(0f, height - lineWidth), radius), stroke);
    }

    @Override
    public void text(String text, float x, float y, float size, Color color) {
        if (canvas == null || text == null || text.isEmpty() || color == null || color.a() == 0) {
            return;
        }
        Font font = font(size);
        fill.setColor(color.packed());
        // Skija 的 y 是基线位置：组件树按「左上角 + 字号高度」给坐标，这里下移一个 ascent。
        float baseline = y + font.getMetrics().getAscent() * -1f;
        canvas.drawString(text, x, baseline, font, fill);
    }

    @Override
    public float textWidth(String text, float size) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        return font(size).measureTextWidth(text);
    }

    @Override
    public float textHeight(float size) {
        return font(size).getMetrics().getHeight();
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
}
