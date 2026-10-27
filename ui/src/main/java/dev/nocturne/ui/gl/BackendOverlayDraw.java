package dev.nocturne.ui.gl;

import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.ui.render.Color;
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
    public void outline(float x, float y, float width, float height, float thickness, int argb) {
        renderer.outline(x, y, width, height, thickness, Color.of(argb));
        drawCalls++;
    }

    @Override
    public void text(String text, float x, float y, int argb) {
        renderer.text(text, x, y, TEXT_SIZE, Color.of(argb));
        drawCalls++;
    }

    @Override
    public float textWidth(String text) {
        return renderer.textWidth(text, TEXT_SIZE);
    }

    @Override
    public float textHeight() {
        return renderer.textHeight(TEXT_SIZE);
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
