package dev.noturne.ui;

import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 无头 {@link Renderer} 替身：记录组件树请求绘制的调用，供测试断言"画了什么"，
 * 从而在不依赖真实 OpenGL 上下文的情况下验证渲染契约。
 */
public final class RecordingRenderer implements Renderer {

    /** 已记录的绘制调用序列；测试可读取或在两次断言之间 {@code clear()} 重置 */
    public final List<String> calls = new ArrayList<String>();

    /**
     * 统计指定类型的调用次数。
     *
     * @param kind 调用标记，形如 {@code "rect"}、{@code "text:hello"}（text 记录具体文本）
     * @return 匹配次数，无匹配时为 0
     */
    public int count(String kind) {
        int n = 0;
        for (String call : calls) {
            // 只按完整标记比较，避免 "rect" 误匹配 "text:rect" 之类的子串
            if (call.equals(kind)) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        calls.add("rect");
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        calls.add("roundedRect");
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        calls.add("outline");
    }

    /** 文本调用记录带上文本内容，其余绘制只记录类型，便于断言具体文案 */
    @Override
    public void text(String text, float x, float y, float size, Color color) {
        calls.add("text:" + text);
    }

    /**
     * 返回文本宽度估算值。
     *
     * <p>按每字符半宽的固定比例估算，使无字体环境下布局仍可计算。
     */
    @Override
    public float textWidth(String text, float size) {
        return text.length() * size * 0.5f;
    }

    /**
     * 返回文本高度。
     *
     * @param size 字号
     * @return 高度等于字号
     */
    @Override
    public float textHeight(float size) {
        return size;
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        calls.add("pushClip");
    }

    @Override
    public void popClip() {
        calls.add("popClip");
    }
}
