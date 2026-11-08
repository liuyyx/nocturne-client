package dev.nocturne.ui.gl;

import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.DrawPlan;
import dev.nocturne.ui.render.DrawSink;
import dev.nocturne.ui.render.Renderer;
import dev.nocturne.ui.render.TextSink;

/**
 * 把任意 {@link UiBackend} 包成「DrawPlan + 三原语 sink」。
 *
 * <p>上层只调用 {@link Renderer} 接口；几何进入 {@link DrawPlan}，每帧 flush 时再变成
 * fill/blit/scissor。文本、纹理上传和玩家模型仍直接委托给后端，因为它们需要后端自己的
 * 字形图集/原生入口。这样 1.13–1.21 不再各自实现圆角/描边。
 */
public final class PlannedBackend implements UiBackend, PlayerModelPreview {

    private final UiBackend delegate;
    private final DrawPlan plan;
    private final DrawSink sink;
    private int clipDepth;

    public PlannedBackend(UiBackend delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate == null");
        }
        this.delegate = delegate;
        this.plan = new DrawPlan(new TextSink() {
            @Override
            public void draw(String text, float x, float y, float size, Color color) {
                PlannedBackend.this.delegate.text(text, x, y, size, color);
            }

            @Override
            public float width(String text, float size) {
                return PlannedBackend.this.delegate.textWidth(text, size);
            }

            @Override
            public float height(float size) {
                return PlannedBackend.this.delegate.textHeight(size);
            }
        }, delegate);
        this.sink = new DrawSink() {
            @Override
            public void fill(float x1, float y1, float x2, float y2, Color color) {
                PlannedBackend.this.delegate.rect(x1, y1, x2 - x1, y2 - y1, color);
            }

            @Override
            public void blit(int texture, float x, float y, float width, float height,
                             float u0, float v0, float u1, float v1, Color tint) {
                PlannedBackend.this.delegate.texturedQuad(texture, x, y, width, height,
                        u0, v0, u1, v1, tint.packed());
            }

            @Override
            public void scissor(float x, float y, float width, float height) {
                applyScissor(x, y, width, height);
            }
        };
    }

    /** @return 被包装的后端，供需要具体能力的装配层使用 */
    public UiBackend delegate() {
        return delegate;
    }

    @Override
    public void beginFrame() {
        resetClip();
        delegate.beginFrame();
        plan.begin();
    }

    @Override
    public void endFrame() {
        try {
            plan.flush(sink);
        } finally {
            resetClip();
            delegate.endFrame();
        }
    }

    @Override
    public boolean reliableAlphaBlend() {
        return delegate.reliableAlphaBlend();
    }

    @Override
    public String backendName() {
        return delegate.backendName();
    }

    @Override
    public int width() {
        return delegate.width();
    }

    @Override
    public int height() {
        return delegate.height();
    }

    @Override
    public boolean ready() {
        return delegate.ready();
    }

    @Override
    public int scale() {
        return delegate.scale();
    }

    @Override
    public int rasterScale() {
        return delegate.rasterScale();
    }

    @Override
    public void setScaleOverride(int scale) {
        delegate.setScaleOverride(scale);
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        plan.rect(x, y, width, height, color);
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        plan.roundedRect(x, y, width, height, radius, color);
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        plan.outline(x, y, width, height, lineWidth, color);
    }

    @Override
    public boolean line(float x1, float y1, float x2, float y2, float thickness, Color color) {
        flushNow();
        return delegate.line(x1, y1, x2, y2, thickness, color);
    }

    @Override
    public void text(String text, float x, float y, float size, Color color) {
        // 文本由后端立即提交，几何在 endFrame 才 flush；先冲掉之前的矩形，否则
        // 背景会盖在文字上。裁剪状态在 flush 后仍保留，文本仍受当前 scissor 约束。
        flushNow();
        delegate.text(text, x, y, size, color);
    }

    @Override
    public float textWidth(String text, float size) {
        return delegate.textWidth(text, size);
    }

    @Override
    public float textHeight(float size) {
        return delegate.textHeight(size);
    }

    @Override
    public boolean gradientQuad(float x, float y, float width, float height, Color topLeft,
                                Color topRight, Color bottomRight, Color bottomLeft) {
        flushNow();
        return delegate.gradientQuad(x, y, width, height, topLeft, topRight, bottomRight, bottomLeft);
    }

    @Override
    public int uploadTexture(int id, int width, int height, int[] argb) {
        return delegate.uploadTexture(id, width, height, argb);
    }

    @Override
    public int uploadTextureRegion(int texture, int width, int height, int[] argb,
                                   int x, int y, int regionWidth, int regionHeight) {
        return delegate.uploadTextureRegion(texture, width, height, argb, x, y, regionWidth, regionHeight);
    }

    @Override
    public void deleteTexture(int id) {
        delegate.deleteTexture(id);
    }

    @Override
    public void texturedQuad(int texture, float x, float y, float width, float height,
                             float u0, float v0, float u1, float v1, int argb) {
        plan.texturedQuad(texture, x, y, width, height, u0, v0, u1, v1, argb);
    }

    @Override
    public boolean supportsTextureTint() {
        return delegate.supportsTextureTint();
    }

    @Override
    public boolean supportsTextures() {
        return delegate.supportsTextures();
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        plan.pushClip(x, y, width, height);
    }

    @Override
    public void popClip() {
        plan.popClip();
    }

    @Override
    public boolean drawPlayer(Object player, float x1, float y1, float x2, float y2,
                              float mouseX, float mouseY) {
        flushNow();
        return delegate instanceof PlayerModelPreview
                && ((PlayerModelPreview) delegate).drawPlayer(player, x1, y1, x2, y2, mouseX, mouseY);
    }

    /** 立即提交当前累积的几何，保证立即绘制的文本/渐变/玩家模型不会改变覆盖顺序。 */
    private void flushNow() {
        plan.flush(sink);
    }

    private void applyScissor(float x, float y, float width, float height) {
        while (clipDepth > 0) {
            delegate.popClip();
            clipDepth--;
        }
        if (width > 0f && height > 0f) {
            delegate.pushClip(x, y, width, height);
            clipDepth = 1;
        }
    }

    private void resetClip() {
        while (clipDepth > 0) {
            delegate.popClip();
            clipDepth--;
        }
    }
}
