package dev.nocturne.ui.gl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.20–1.21 的 {@code GuiGraphics} 形态：与 26.x 的 extractor 共用同一个后端，只是
 * 文本方法叫 {@code drawString}、且**没有** {@code guiWidth/guiHeight}。
 *
 * <p>这两个差异都必须被绑定层吸收：签名叫不出来的话后端根本装不上（界面永远不画）；
 * 尺寸方法缺失时如果没有回退，组件树会按 0 排版（同样什么都看不见）。
 */
class ExtractorRendererGuiGraphicsTest {

    /** 假的 {@code GuiGraphics}：只有 1.20–1.21 真实存在的成员。 */
    public static final class FakeGuiGraphics {
        /** 调用记录。 */
        final List<String> calls = new ArrayList<>();

        public void fill(int x1, int y1, int x2, int y2, int color) {
            calls.add("fill " + x1 + "," + y1 + "," + x2 + "," + y2);
        }

        public void drawString(Object font, String text, int x, int y, int color) {
            calls.add("drawString " + text + " " + x + "," + y);
        }

        public void enableScissor(int x1, int y1, int x2, int y2) {
            calls.add("scissor " + x1 + "," + y1 + "," + x2 + "," + y2);
        }

        public void disableScissor() {
            calls.add("scissor-off");
        }
    }

    @Test
    void bindsWhenTextIsCalledDrawString() {
        ExtractorRenderer renderer = ExtractorRenderer.bind(FakeGuiGraphics.class, () -> null);
        assertNotNull(renderer, "1.20–1.21 形态必须能绑定，不能因方法名不同而拒绝");
    }

    @Test
    void fillReachesTheGraphicsObject() {
        FakeGuiGraphics graphics = new FakeGuiGraphics();
        ExtractorRenderer renderer = ExtractorRenderer.bind(FakeGuiGraphics.class, () -> null);
        assertNotNull(renderer);

        renderer.setFrame(graphics);
        renderer.beginFrame();
        renderer.rect(10f, 20f, 30f, 40f, dev.nocturne.ui.render.Color.WHITE);

        assertTrue(graphics.calls.contains("fill 10,20,40,60"), "矩形应落到 fill: " + graphics.calls);
    }

    @Test
    void missingSizeMethodsFallBackToWindow() {
        FakeGuiGraphics graphics = new FakeGuiGraphics();
        ExtractorRenderer renderer = ExtractorRenderer.bind(FakeGuiGraphics.class, () -> null);
        assertNotNull(renderer);

        renderer.setFrame(graphics);
        // 没有 guiWidth/guiHeight、也没有 Minecraft 实例：不崩、尺寸保持 0（下一帧再试）。
        renderer.beginFrame();
        assertEquals(0, renderer.width());
        assertEquals(0, renderer.height());
    }
}
