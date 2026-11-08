package dev.nocturne.ui.gl;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 26.3 实测回归：{@code GuiGraphicsExtractor.disableScissor()} 就是 {@code scissorStack.pop()}，
 * 没有配对的 push 时会抛 {@code NoSuchElementException}（真机报
 * {@code extractor backend draw call failed (disableScissor): InvocationTargetException}）。
 *
 * <p>替身严格模拟该行为：空栈 pop 直接抛异常。三条不变式：空矩形不下发原生裁剪、嵌套 push/pop
 * 逐一配对、帧首把残留层全部排空。
 */
class ExtractorScissorBalanceTest {

    /** 严格栈语义的 26.3 extractor 替身。 */
    public static final class StrictScissorExtractor {
        int depth;
        int enables;
        int disables;

        public void fill(int x1, int y1, int x2, int y2, int color) {
        }

        public void text(Object font, String value, int x, int y, int argb) {
        }

        public void enableScissor(int x1, int y1, int x2, int y2) {
            enables++;
            depth++;
        }

        public void disableScissor() {
            disables++;
            if (depth == 0) {
                throw new NoSuchElementException("scissor stack is empty");
            }
            depth--;
        }
    }

    @Test
    void emptyClipRectangleDoesNotTouchTheNativeStack() {
        ExtractorRenderer renderer = ExtractorRenderer.bind(StrictScissorExtractor.class, () -> null);
        assertNotNull(renderer);
        StrictScissorExtractor graphics = new StrictScissorExtractor();
        renderer.setFrame(graphics);
        renderer.beginFrame();

        renderer.pushClip(10f, 10f, 0f, 0f);
        renderer.popClip();
        assertEquals(0, graphics.depth, "no native scissor may be popped for a skipped push");
        assertEquals(0, graphics.disables);
    }

    @Test
    void nestedPushPopStaysBalanced() {
        ExtractorRenderer renderer = ExtractorRenderer.bind(StrictScissorExtractor.class, () -> null);
        assertNotNull(renderer);
        StrictScissorExtractor graphics = new StrictScissorExtractor();
        renderer.setFrame(graphics);
        renderer.beginFrame();

        renderer.pushClip(0f, 0f, 100f, 100f);
        renderer.pushClip(10f, 10f, 50f, 50f);
        assertEquals(2, graphics.depth, "two nested clips must push twice");

        renderer.popClip();
        assertEquals(1, graphics.depth, "inner pop must return to the parent rect");
        renderer.popClip();
        assertEquals(0, graphics.depth, "outer pop must clear the stack");
        assertEquals(2, graphics.enables);
        assertEquals(2, graphics.disables);
    }

    @Test
    void frameStartDrainsEveryLeftoverScissor() {
        ExtractorRenderer renderer = ExtractorRenderer.bind(StrictScissorExtractor.class, () -> null);
        assertNotNull(renderer);
        StrictScissorExtractor graphics = new StrictScissorExtractor();
        renderer.setFrame(graphics);
        renderer.beginFrame();

        renderer.pushClip(0f, 0f, 100f, 100f);
        renderer.pushClip(10f, 10f, 50f, 50f);
        assertEquals(2, graphics.depth);

        renderer.beginFrame();
        assertEquals(0, graphics.depth, "frame start must drain every leftover layer: "
                + "depth=" + graphics.depth);
    }
}
