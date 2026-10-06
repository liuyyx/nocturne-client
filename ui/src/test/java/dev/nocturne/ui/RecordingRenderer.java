package dev.nocturne.ui;

import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;

import java.util.ArrayList;
import java.util.List;

/**
 * 无头 {@link Renderer} 替身：按顺序记录每次绘制调用的<b>类型与参数</b>，供测试断言
 * 「画了什么、按什么顺序、是否成对」，从而在不依赖真实 OpenGL 上下文的情况下验证渲染契约。
 *
 * <p>只计数无法发现顺序类缺陷（HUD 元素顺序反转、pushClip/popClip 不配对、开关旋钮位置不推进）。
 * 因此除了兼容用的 {@link #calls}（类型标记，供 {@link #count(String)}），本类还保留
 * {@link #drawCalls} 结构化记录（矩形坐标、颜色、文本内容等）。
 */
public final class RecordingRenderer implements Renderer {

    /** 已记录的绘制调用类型标记；测试可读取或 {@code clear()} 重置。与 {@link #drawCalls} 一一对应 */
    public final List<String> calls = new ArrayList<String>();
    /** 与 {@link #calls} 对齐的结构化记录，携带全部参数 */
    public final List<DrawCall> drawCalls = new ArrayList<DrawCall>();

    /** 一次绘制调用的结构化记录。 */
    public static final class DrawCall {
        /** 调用类型：{@code rect} / {@code roundedRect} / {@code outline} / {@code text} / {@code pushClip} / {@code popClip} */
        public final String kind;
        /** 矩形左边界（或文本 x） */
        public final float x;
        /** 矩形上边界（或文本 y） */
        public final float y;
        /** 宽度；文本调用为 0 */
        public final float width;
        /** 高度；文本调用为 0 */
        public final float height;
        /** 圆角半径；非圆角调用为 0 */
        public final float radius;
        /** 线宽；非描边调用为 0 */
        public final float lineWidth;
        /** 文本字号；非文本调用为 0 */
        public final float textSize;
        /** 颜色；可能为 null（调用方传了 null 时） */
        public final Color color;
        /** 文本内容；非文本调用为 null */
        public final String text;

        private DrawCall(String kind, float x, float y, float width, float height,
                         float radius, float lineWidth, float textSize, Color color, String text) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.radius = radius;
            this.lineWidth = lineWidth;
            this.textSize = textSize;
            this.color = color;
            this.text = text;
        }
    }

    /** 清空全部记录；两次断言之间调用 */
    public void clear() {
        calls.clear();
        drawCalls.clear();
    }

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

    /** @return 类型为 {@code kind} 的结构化记录（保持原顺序） */
    public List<DrawCall> ofKind(String kind) {
        List<DrawCall> out = new ArrayList<DrawCall>();
        for (DrawCall call : drawCalls) {
            if (call.kind.equals(kind)) {
                out.add(call);
            }
        }
        return out;
    }

    /** @return 与 {@link #calls} 顺序一致的纯类型序列，供顺序断言 */
    public List<String> kinds() {
        List<String> out = new ArrayList<String>();
        for (DrawCall call : drawCalls) {
            out.add(call.kind);
        }
        return out;
    }

    /** @return 全部绘制完成后的裁剪嵌套深度（pushClip 减 popClip） */
    public int clipDepth() {
        int depth = 0;
        for (DrawCall call : drawCalls) {
            if ("pushClip".equals(call.kind)) {
                depth++;
            } else if ("popClip".equals(call.kind)) {
                depth--;
            }
        }
        return depth;
    }

    /** @return 绘制过程中出现过的最大裁剪嵌套深度 */
    public int maxClipDepth() {
        int depth = 0;
        int max = 0;
        for (DrawCall call : drawCalls) {
            if ("pushClip".equals(call.kind)) {
                depth++;
                max = Math.max(max, depth);
            } else if ("popClip".equals(call.kind)) {
                depth--;
            }
        }
        return max;
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        calls.add("rect");
        drawCalls.add(new DrawCall("rect", x, y, width, height, 0f, 0f, 0f, color, null));
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        calls.add("roundedRect");
        drawCalls.add(new DrawCall("roundedRect", x, y, width, height, radius, 0f, 0f, color, null));
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        calls.add("outline");
        drawCalls.add(new DrawCall("outline", x, y, width, height, 0f, lineWidth, 0f, color, null));
    }

    /** 文本调用记录带上文本内容，其余绘制只记录类型，便于断言具体文案 */
    @Override
    public void text(String text, float x, float y, float size, Color color) {
        calls.add("text:" + text);
        drawCalls.add(new DrawCall("text", x, y, 0f, 0f, 0f, 0f, size, color, text));
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
        drawCalls.add(new DrawCall("pushClip", x, y, width, height, 0f, 0f, 0f, null, null));
    }

    @Override
    public void popClip() {
        calls.add("popClip");
        drawCalls.add(new DrawCall("popClip", 0f, 0f, 0f, 0f, 0f, 0f, 0f, null, null));
    }
}
