package dev.nocturne.ui.fatality;

import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;
import dev.nocturne.ui.text.RasterTextures;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 面向 {@link Renderer} 的 {@link FaDraw} 实现：把 Fatality 的绘制词汇翻译成
 * 「矩形 / 圆角矩形 / 描边 / 纹理四边形 / 文字」五类原语，三个后端都能画。
 *
 * <p>变换由本类自持（{@code tx/ty/s}）：{@link Renderer} 没有变换栈，而界面整体要按一个缩放因子
 * 绘制。所有绘制调用都把设计单位换算成设备坐标（后端逻辑像素）后再下发；裁剪用
 * {@link Renderer#pushClip}/{@link Renderer#popClip}，本类额外维护求交后的矩形（嵌套裁剪取交集）。
 *
 * <p>渐变（{@code quad}）按较短边分段近似；阴影与圆角描边的角弧走 {@link RasterTextures} 生成的
 * 纹理（后端不支持纹理时退化为矩形堆叠近似）。
 */
final class BackendFaDraw extends FaDraw {

    /** 渐变分段上限：再多也看不出来，只是白白多几次绘制调用。 */
    private static final int GRADIENT_MAX_SEGMENTS = 64;

    /** 当前绘制面。 */
    private Renderer renderer;
    /** 累积平移 x（设备像素）。 */
    private float tx;
    /** 累积平移 y（设备像素）。 */
    private float ty;
    /** 累积缩放（设备像素 / 设计单位）。 */
    private float s = 1.0f;
    /** 累积不透明度。 */
    private float alpha = 1.0f;
    /** 当前裁剪矩形（设备像素）：{@code {x, y, w, h}}；无裁剪时为 {@code null}。 */
    private float[] clip;
    /** {@link #push()} 保存的变换/透明度栈。 */
    private final Deque<float[]> saved = new ArrayDeque<float[]>();
    /** {@link #push()} 保存的裁剪栈。 */
    private final Deque<float[]> savedClips = new ArrayDeque<float[]>();

    /**
     * 开始一帧。
     *
     * @param target       本帧绘制面
     * @param initialScale 设计单位到设备像素的缩放（{@code FaClickGui.scale}）
     */
    void begin(Renderer target, float initialScale) {
        renderer = target;
        tx = 0.0f;
        ty = 0.0f;
        s = initialScale <= 0f ? 1.0f : initialScale;
        alpha = 1.0f;
        clip = null;
        saved.clear();
        savedClips.clear();
    }

    /** @return 设计单位到设备像素的缩放 */
    float scale() {
        return s;
    }

    @Override
    void rect(float x, float y, float w, float h, float radius, int argb) {
        if (w <= 0.0f || h <= 0.0f || alpha() == 0) {
            return;
        }
        float rx = gx(x), ry = gy(y), rw = w * s, rh = h * s, r = radius * s;
        if (r <= 0.5f) {
            renderer.rect(rx, ry, rw, rh, Color.of(modulate(argb)));
            return;
        }
        renderer.roundedRect(rx, ry, rw, rh, r, Color.of(modulate(argb)));
    }

    @Override
    void outline(float x, float y, float w, float h, float radius, float width, int argb) {
        if (w <= 0.0f || h <= 0.0f || width <= 0.0f || alpha() == 0) {
            return;
        }
        int color = modulate(argb);
        float t = width;
        float r = Math.max(0.0f, Math.min(radius, Math.min(w, h) * 0.5f));
        if (t > Math.min(w, h) * 0.5f) {
            // 线宽超过半宽时退化成实心填充，避免两条边在中部重叠画两遍（双混变实）。
            rect(x, y, w, h, r, argb);
            return;
        }
        int cornerTexture = RasterTextures.cornerRing(renderer, r, t, s);
        if (cornerTexture > 0) {
            // 四条直边用普通矩形，四个角用纹理化的四分之一环（UV 翻转复用同一张）。
            rect(x + r, y, w - 2.0f * r, t, 0.0f, argb);
            rect(x + r, y + h - t, w - 2.0f * r, t, 0.0f, argb);
            rect(x, y + r, t, h - 2.0f * r, 0.0f, argb);
            rect(x + w - t, y + r, t, h - 2.0f * r, 0.0f, argb);
            // 角块：左上不翻转、右上 u 翻转、右下 uv 翻转、左下 v 翻转。
            texturedCorner(cornerTexture, x, y, r, color, false, false);
            texturedCorner(cornerTexture, x + w - r, y, r, color, true, false);
            texturedCorner(cornerTexture, x + w - r, y + h - r, r, color, true, true);
            texturedCorner(cornerTexture, x, y + h - r, r, color, false, true);
            return;
        }
        // 无纹理后端：直边 + 沿圆弧铺小方块近似角弧。半径通常 ≤ 8 设计单位，误差看不出来。
        rect(x + r, y, w - 2.0f * r, t, 0.0f, argb);
        rect(x + r, y + h - t, w - 2.0f * r, t, 0.0f, argb);
        rect(x, y + r, t, h - 2.0f * r, 0.0f, argb);
        rect(x + w - t, y + r, t, h - 2.0f * r, 0.0f, argb);
        if (r > 0.0f) {
            int steps = Math.max(1, (int) Math.ceil(r));
            arcBlocks(x + r, y + r, r, 180.0f, 270.0f, t, steps, argb);
            arcBlocks(x + w - r, y + r, r, 270.0f, 360.0f, t, steps, argb);
            arcBlocks(x + w - r, y + h - r, r, 0.0f, 90.0f, t, steps, argb);
            arcBlocks(x + r, y + h - r, r, 90.0f, 180.0f, t, steps, argb);
        }
    }

    @Override
    void quad(float x, float y, float w, float h, int topLeft, int topRight, int bottomRight,
              int bottomLeft) {
        if (w <= 0.0f || h <= 0.0f) {
            return;
        }
        if (topLeft == topRight && topRight == bottomRight && bottomRight == bottomLeft) {
            rect(x, y, w, h, 0.0f, topLeft);
            return;
        }
        // 平涂后端没有逐顶点色：沿较短边分段，每段取双线性插值后的颜色。
        boolean horizontal = w <= h;
        float extent = horizontal ? w : h;
        int segments = Math.max(1, Math.min(GRADIENT_MAX_SEGMENTS, (int) Math.ceil(extent)));
        float step = extent / segments;
        for (int i = 0; i < segments; i++) {
            float tm = (i + 0.5f) / segments;
            if (horizontal) {
                // 段中心处的上下两端色各取一次，再用平均值当作该段的平涂色。
                int top = FaTheme.mix(topLeft, topRight, tm);
                int bottom = FaTheme.mix(bottomLeft, bottomRight, tm);
                rect(x + i * step, y, step + 0.5f, h, 0.0f, FaTheme.mix(top, bottom, 0.5f));
            } else {
                int left = FaTheme.mix(topLeft, bottomLeft, tm);
                int right = FaTheme.mix(topRight, bottomRight, tm);
                rect(x, y + i * step, w, step + 0.5f, 0.0f, FaTheme.mix(left, right, 0.5f));
            }
        }
    }

    @Override
    void shadow(float x, float y, float w, float h, float radius, float sigma, int argb) {
        if (w <= 0.0f || h <= 0.0f || sigma <= 0.0f || alpha() == 0) {
            return;
        }
        int color = modulate(argb);
        int texture = RasterTextures.shadow(renderer, w, h, radius, sigma, s);
        if (texture > 0) {
            float margin = (float) Math.ceil(sigma * 3.0f);
            renderer.texturedQuad(texture, gx(x - margin), gy(y - margin),
                    (w + margin * 2.0f) * s, (h + margin * 2.0f) * s, 0f, 0f, 1f, 1f, color);
            return;
        }
        // 无纹理后端：三层外扩的半透明圆角矩形，视觉上接近一条柔和的外环。
        for (int i = 3; i >= 1; i--) {
            float spread = sigma * i;
            int shade = FaTheme.alpha(color, 0.16f * (4 - i));
            rect(x - spread, y - spread, w + spread * 2.0f, h + spread * 2.0f, radius + spread, shade);
        }
    }

    @Override
    void text(String value, float x, float y, FaFont font, int argb) {
        if (value == null || value.isEmpty() || alpha() == 0 || font == null) {
            return;
        }
        // 显示层统一翻译（幂等：词条键是英文，中文再查一次查不到，原样返回）。
        font.draw(renderer, FaStrings.t(value), gx(x), gy(y), modulate(argb));
    }

    @Override
    Scope push() {
        saved.push(new float[]{tx, ty, s, alpha});
        savedClips.push(clip == null ? new float[0] : clip);
        return new Scope() {
            @Override
            public void close() {
                float[] state = saved.pop();
                tx = state[0];
                ty = state[1];
                s = state[2];
                alpha = state[3];
                float[] previous = savedClips.pop();
                clip = previous.length == 0 ? null : previous;
            }
        };
    }

    @Override
    void translate(float x, float y) {
        tx += x * s;
        ty += y * s;
    }

    @Override
    void scale(float factor) {
        s *= factor;
    }

    @Override
    void opacity(float value) {
        alpha *= Math.max(0.0f, Math.min(1.0f, value));
    }

    @Override
    Scope clip(float x, float y, float w, float h) {
        final Scope state = push();
        float nx = gx(x);
        float ny = gy(y);
        float nw = Math.max(0.0f, w * s);
        float nh = Math.max(0.0f, h * s);
        float[] next = {nx, ny, nw, nh};
        if (clip != null) {
            float left = Math.max(clip[0], next[0]);
            float top = Math.max(clip[1], next[1]);
            float right = Math.min(clip[0] + clip[2], next[0] + next[2]);
            float bottom = Math.min(clip[1] + clip[3], next[1] + next[3]);
            next = new float[]{left, top, Math.max(0.0f, right - left), Math.max(0.0f, bottom - top)};
        }
        clip = next;
        // Renderer 的裁剪以设备像素为单位、且要能嵌套；直接把求交后的矩形压栈即可。
        renderer.pushClip(next[0], next[1], next[2], next[3]);
        // 关闭时必须既弹出 Renderer 的裁剪、又恢复本类的变换/透明度状态——只做后者会让裁剪
        // 逐层残留：外层裁剪会与外层矩形求交成空，界面上表现为"整块内容凭空消失"。
        return new Scope() {
            @Override
            public void close() {
                renderer.popClip();
                state.close();
            }
        };
    }

    /** @return 当前不透明度的 0–255 取整值，用于「完全不透明时不解引用渲染器」的短路 */
    private int alpha() {
        return Math.round(alpha * 255.0f);
    }

    /** 把累积不透明度乘进颜色。 */
    private int modulate(int argb) {
        return alpha >= 1.0f ? argb : FaTheme.alpha(argb, alpha);
    }

    /** 设备坐标 x。 */
    private float gx(float x) {
        return tx + x * s;
    }

    /** 设备坐标 y。 */
    private float gy(float y) {
        return ty + y * s;
    }

    /** 用一个纹理角块画顶点（{@code flipU}/{@code flipV} 控制镜像，四角复用同一张纹理）。 */
    private void texturedCorner(int texture, float x, float y, float size, int color, boolean flipU,
                               boolean flipV) {
        float u0 = flipU ? 1f : 0f;
        float u1 = flipU ? 0f : 1f;
        float v0 = flipV ? 1f : 0f;
        float v1 = flipV ? 0f : 1f;
        renderer.texturedQuad(texture, gx(x), gy(y), size * s, size * s, u0, v0, u1, v1, color);
    }

    /** 沿一段圆弧铺小方块，用于无纹理后端近似角弧。 */
    private void arcBlocks(float cx, float cy, float radius, float startDeg, float endDeg, float width,
                           int steps, int argb) {
        for (int i = 0; i < steps; i++) {
            double angle = Math.toRadians(startDeg + (endDeg - startDeg) * (i + 0.5) / steps);
            float px = cx + (float) Math.cos(angle) * radius;
            float py = cy + (float) Math.sin(angle) * radius;
            rect(px - width * 0.5f, py - width * 0.5f, width, width, 0.0f, argb);
        }
    }
}
