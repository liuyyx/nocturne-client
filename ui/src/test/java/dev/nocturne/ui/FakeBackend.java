package dev.nocturne.ui;

import dev.nocturne.ui.gl.UiBackend;
import dev.nocturne.ui.render.Color;

/**
 * {@link UiBackend} 的测试替身：绘制调用转发给内部 {@link RecordingRenderer}，
 * 每帧准备/收尾只计数，尺寸与就绪状态可写。
 */
public final class FakeBackend implements UiBackend {

    /** 承接全部绘制调用的记录器 */
    public final RecordingRenderer renderer = new RecordingRenderer();
    /** {@link #beginFrame()} 被调用的次数 */
    public int beginCalls;
    /** {@link #endFrame()} 被调用的次数 */
    public int endCalls;
    /** {@link #backendName()} 的返回值 */
    public String name = "fake";
    /** 绘制区域尺寸 */
    public int width;
    /** 绘制区域尺寸 */
    public int height;
    /** {@link #ready()} 的返回值 */
    public boolean ready = true;

    /**
     * @param width  绘制区域宽度
     * @param height 绘制区域高度
     */
    public FakeBackend(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void beginFrame() {
        beginCalls++;
    }

    @Override
    public void endFrame() {
        endCalls++;
    }

    @Override
    public String backendName() {
        return name;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public boolean ready() {
        return ready;
    }

    @Override
    public void rect(float x, float y, float w, float h, Color color) {
        renderer.rect(x, y, w, h, color);
    }

    @Override
    public void roundedRect(float x, float y, float w, float h, float radius, Color color) {
        renderer.roundedRect(x, y, w, h, radius, color);
    }

    @Override
    public void outline(float x, float y, float w, float h, float lineWidth, Color color) {
        renderer.outline(x, y, w, h, lineWidth, color);
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
    public void pushClip(float x, float y, float w, float h) {
        renderer.pushClip(x, y, w, h);
    }

    @Override
    public void popClip() {
        renderer.popClip();
    }
}
