package dev.nocturne.ui.gl;

import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.fatality.FaFont;
import dev.nocturne.ui.text.FontRasterizer;
import dev.nocturne.ui.render.Renderer;

/**
 * 把 ui 的 {@link Renderer} 适配成 client 侧的 {@link OverlayDraw}。
 *
 * <p>世界覆盖层模块只认 {@code OverlayDraw}（client 侧接口，避免 client 依赖 ui），而实际绘制能力在
 * {@link Renderer} 上——三个后端都实现了它，所以适配器一份就够，模块不用关心当前是固定管线、
 * 核心 profile，还是 26.x 的 extractor。
 *
 * <p>字号固定为 {@value #TEXT_SIZE}：这是游戏字体的原生行高，三个后端在无缩放控制时都按它绘制，
 * 于是 {@link #textWidth} 与 {@link #text} 口径一致，标签不会错位。
 */
public final class BackendOverlayDraw implements OverlayDraw {

    /** 覆盖层文本的字号（游戏字体原生行高）。 */
    private static final float TEXT_SIZE = 9f;

    /** 实际绘制面。 */
    private final Renderer renderer;
    /** 尺寸与就绪状态来自后端（Renderer 本身不暴露视口）。 */
    private final UiBackend backend;

    /** 已向后端下发的覆盖层图元数（仅供首帧有效绘制诊断）。 */
    private long drawCalls;
    private boolean rasterFontsFailed;

    /**
     * @param backend 当帧绘制后端；同时用作 Renderer 与尺寸来源
     */
    public BackendOverlayDraw(UiBackend backend) {
        this.backend = backend;
        this.renderer = backend;
    }

    /** @return 已向绘制后端下发的图元总数；调用前后取差可判断本帧是否真有图元。 */
    long drawCalls() {
        return drawCalls;
    }

    @Override
    public void rect(float x, float y, float width, float height, int argb) {
        renderer.rect(x, y, width, height, Color.of(argb));
        drawCalls++;
    }

    @Override
    public void quad(float x0, float y0, float x1, float y1,
                     float x2, float y2, float x3, float y3, int argb) {
        if (!renderer.quad(x0, y0, x1, y1, x2, y2, x3, y3, Color.of(argb))) {
            OverlayDraw.super.quad(x0, y0, x1, y1, x2, y2, x3, y3, argb);
        }
        drawCalls++;
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, int argb) {
        renderer.roundedRect(x, y, width, height, radius, Color.of(argb));
        drawCalls++;
    }

    @Override
    public void outline(float x, float y, float width, float height, float thickness, int argb) {
        renderer.outline(x, y, width, height, thickness, Color.of(argb));
        drawCalls++;
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, float thickness, int argb) {
        if (!renderer.line(x1, y1, x2, y2, thickness, Color.of(argb))) {
            OverlayDraw.super.line(x1, y1, x2, y2, thickness, argb);
        }
        drawCalls++;
    }

    @Override
    public int uploadTexture(int id, int width, int height, int[] argb) {
        return renderer.uploadTexture(id, width, height, argb);
    }

    @Override
    public void deleteTexture(int id) {
        renderer.deleteTexture(id);
    }

    @Override
    public void texturedQuad(int texture, float x, float y, float width, float height,
                             float u0, float v0, float u1, float v1, int argb) {
        renderer.texturedQuad(texture, x, y, width, height, u0, v0, u1, v1, argb);
        drawCalls++;
    }

    @Override
    public boolean supportsTextures() {
        return renderer.supportsTextures();
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        renderer.pushClip(x, y, width, height);
    }

    @Override
    public void popClip() {
        renderer.popClip();
    }

    /**
     * 能力位由后端自己声明（{@link UiBackend#reliableAlphaBlend()}），适配器不再按
     * {@code instanceof GlRenderer} 猜：以前那种写法对任何**未知**后端都是 fail-open，
     * 新后端一上线就默认拿到"可以画整帧遮罩"的许可。
     */
    @Override
    public boolean reliableAlphaBlend() {
        return backend.reliableAlphaBlend();
    }

    @Override
    public void text(String text, float x, float y, int argb) {
        text(text, x, y, TEXT_SIZE, argb);
    }

    @Override
    public float textWidth(String text) {
        return textWidth(text, TEXT_SIZE);
    }

    @Override
    public float textHeight() {
        return textHeight(TEXT_SIZE);
    }

    @Override
    public void text(String text, float x, float y, float size, int argb) {
        FaFont font = font(size);
        if (font != null) {
            font.draw(renderer, text, x, y, argb);
        } else {
            renderer.text(text, x, y, size, Color.of(argb));
        }
        drawCalls++;
    }

    @Override
    public float textWidth(String text, float size) {
        FaFont font = font(size);
        return font == null ? renderer.textWidth(text, size) : font.width(text);
    }

    @Override
    public float textHeight(float size) {
        FaFont font = font(size);
        return font == null ? renderer.textHeight(size) : font.height();
    }

    private FaFont font(float size) {
        // 固定管线与游戏绘制栈已有可用的原生文字面；core profile 才需要 CPU 字形图集。
        if (!(backend instanceof ModernRenderer) || rasterFontsFailed || !renderer.supportsTextures()) {
            return null;
        }
        try {
            return FontRasterizer.overlayFont(renderer, Math.max(1, backend.rasterScale()), size);
        } catch (Throwable failure) {
            rasterFontsFailed = true;
            System.out.println("[nocturne] overlay font atlas unavailable: " + failure
                    + "; falling back to the game font");
            return null;
        }
    }

    @Override
    public int width() {
        return backend.width();
    }

    @Override
    public int height() {
        return backend.height();
    }
}
