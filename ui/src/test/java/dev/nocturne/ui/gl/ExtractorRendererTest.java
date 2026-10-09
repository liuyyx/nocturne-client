package dev.nocturne.ui.gl;

import dev.nocturne.ui.render.Color;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtractorRenderer} 的行为测试。
 *
 * <p>钉住的是「绘制原语 → extractor 调用」的**参数口径**：坐标是左上/右下还是左上+宽高、颜色怎么
 * 打包、裁剪用哪一对方法、字号怎么处理。这些在真机上错了不会报错——只会画歪或画不出来，而真机验证
 * 一轮代价很高，所以先用替身类把口径钉死。
 *
 * <p>替身类用**真实签名**（{@code fill(IIIII)V} / {@code outline(IIIII)V} / {@code text} 首参为
 * 引用类型 / {@code enableScissor(IIII)V}），这样 {@link ExtractorRenderer#bind} 的签名解析路径
 * 也被一并覆盖：签名对不上时它必须返回 {@code null}，而不是产出一个画不出东西的后端。
 */
class ExtractorRendererTest {

    /** 假的绘制上下文：按真实签名记录每次调用。 */
    public static final class FakeExtractor {
        /** 调用记录，形如 {@code "fill 10,20,40,60 80ff0000"}。 */
        final List<String> calls = new ArrayList<>();

        public int guiWidth() {
            return 320;
        }

        public int guiHeight() {
            return 240;
        }

        public void fill(int x1, int y1, int x2, int y2, int argb) {
            calls.add("fill " + x1 + "," + y1 + "," + x2 + "," + y2 + " " + hex(argb));
        }

        public void outline(int x, int y, int width, int height, int argb) {
            calls.add("outline " + x + "," + y + "," + width + "," + height + " " + hex(argb));
        }

        public void enableScissor(int x1, int y1, int x2, int y2) {
            calls.add("enableScissor " + x1 + "," + y1 + "," + x2 + "," + y2);
        }

        public void disableScissor() {
            calls.add("disableScissor");
        }

        public void text(Object font, String value, int x, int y, int argb) {
            calls.add("text " + value + " " + x + "," + y + " " + hex(argb));
        }

        private static String hex(int argb) {
            return Integer.toHexString(argb);
        }
    }

    /** 绘制区尺寸报 0 的上下文：这一帧量不到尺寸时不得把已知尺寸改小。 */
    public static final class ZeroExtractor {
        public int guiWidth() {
            return 0;
        }

        public int guiHeight() {
            return 0;
        }

        public void fill(int x1, int y1, int x2, int y2, int argb) {
        }

        public void text(Object font, String value, int x, int y, int argb) {
        }
    }

    /** 缺 {@code fill} 的绘制上下文：签名不全时不得产出后端。 */
    public static final class IncompleteExtractor {
        public int guiWidth() {
            return 320;
        }

        public int guiHeight() {
            return 240;
        }

        public void text(Object font, String value, int x, int y, int argb) {
        }
    }

    /** 假的游戏字体：只有宽度与行高（真实 26.x 的 Font 也只有这两样与绘制无关的度量）。 */
    public static final class FakeFont {
        /** 行高字段（真实 Font 是 public final int）。 */
        public final int lineHeight = 9;

        public int width(String text) {
            return text.length() * 6;
        }
    }

    /** 假的 Minecraft 实例：字体挂在 {@code font} 字段上。 */
    public static final class FakeMinecraft {
        /** 字体（真实 Minecraft 是 public final Font）。 */
        public final Object font = new FakeFont();
    }

    /** 绑定一个可用后端；字体来源是假实例。 */
    private static ExtractorRenderer backend() {
        ExtractorRenderer renderer = ExtractorRenderer.bind(FakeExtractor.class,
                () -> new FakeMinecraft());
        assertNotNull(renderer, "bind must succeed for a signature-complete extractor");
        return renderer;
    }

    /**
     * 绘制区尺寸来自 extractor，且**跨帧保留**。
     *
     * <p>保留是必须的：输入层在帧回调里要用它把鼠标坐标换算成逻辑坐标，而帧回调早于本帧的
     * extract 阶段——清零会让换算读到 0、整个界面的点击落空（26.3 实测踩过）。
     * {@code ready()} 仍然只在帧内为真：尺寸是窗口属性，上下文不是。
     */
    @Test
    void keepsSizeAcrossFramesButNotReadiness() {
        ExtractorRenderer renderer = backend();
        assertFalse(renderer.ready(), "no draw context yet: must not claim to be ready");
        assertEquals(0, renderer.width(), "no measurement yet");

        renderer.setFrame(new FakeExtractor());
        renderer.beginFrame();
        assertTrue(renderer.ready());
        assertEquals(320, renderer.width());
        assertEquals(240, renderer.height());

        renderer.clearFrame();
        assertFalse(renderer.ready(), "after the frame is over the context is gone");
        assertEquals(320, renderer.width(), "the measured size must survive the frame");
        assertEquals(240, renderer.height());
    }

    /** extractor 报 0 时保留上一次有效尺寸：0 是"这一帧没量到"，不是"窗口变成 0 宽"。 */
    @Test
    void keepsLastValidSizeWhenExtractorReportsZero() {
        ExtractorRenderer renderer = backend();
        renderer.setFrame(new FakeExtractor());
        renderer.beginFrame();
        assertEquals(320, renderer.width());

        renderer.setFrame(new ZeroExtractor());
        renderer.beginFrame();

        assertEquals(320, renderer.width());
        assertEquals(240, renderer.height());
    }

    /** 矩形：{@code fill} 收的是左上/右下两个角（不是左上 + 宽高），颜色按 ARGB 原样传。 */
    @Test
    void rectBecomesFillWithTwoCorners() {
        ExtractorRenderer renderer = backend();
        FakeExtractor extractor = new FakeExtractor();
        renderer.setFrame(extractor);
        renderer.beginFrame();

        renderer.rect(10f, 20f, 30f, 40f, Color.argb(0x80, 0xFF, 0x00, 0x00));

        assertEquals(Arrays.asList("fill 10,20,40,60 80ff0000"), extractor.calls);
    }

    /** 退化尺寸必须被跳过：取整后塌陷的矩形会让游戏侧收到零宽矩形。 */
    @Test
    void skipsDegenerateRects() {
        ExtractorRenderer renderer = backend();
        FakeExtractor extractor = new FakeExtractor();
        renderer.setFrame(extractor);
        renderer.beginFrame();

        renderer.rect(10f, 20f, 0f, 40f, Color.WHITE);
        renderer.rect(10f, 20f, 0.2f, 40f, Color.WHITE);
        renderer.rect(10f, 20f, 30f, 40f, null);

        assertTrue(extractor.calls.isEmpty(), "degenerate rects must not reach the extractor");
    }

    /** 描边走 extractor 自己的 {@code outline}（参数是左上 + 宽高）。 */
    @Test
    void outlineUsesExtractorOutline() {
        ExtractorRenderer renderer = backend();
        FakeExtractor extractor = new FakeExtractor();
        renderer.setFrame(extractor);
        renderer.beginFrame();

        renderer.outline(1f, 2f, 3f, 4f, 1f, Color.WHITE);

        assertEquals(Arrays.asList("outline 1,2,3,4 ffffffff"), extractor.calls);
    }

    /** 裁剪用 extractor 的 scissor 栈（参数同样是两个角），成对调用。 */
    @Test
    void clipUsesScissorStack() {
        ExtractorRenderer renderer = backend();
        FakeExtractor extractor = new FakeExtractor();
        renderer.setFrame(extractor);
        renderer.beginFrame();

        renderer.pushClip(0f, 0f, 100f, 50f);
        renderer.popClip();

        assertEquals(Arrays.asList("enableScissor 0,0,100,50", "disableScissor"), extractor.calls);
    }

    /** 文字与度量：字号被忽略（26.x 的 Font 只有原生尺寸），度量必须与绘制同基准。 */
    @Test
    void textAndMetricsUseTheGameFont() {
        ExtractorRenderer renderer = backend();
        FakeExtractor extractor = new FakeExtractor();
        renderer.setFrame(extractor);
        renderer.beginFrame();

        renderer.text("hi", 5f, 6f, 9f, Color.WHITE);

        assertEquals(Arrays.asList("text hi 5,6 ffffffff"), extractor.calls);
        assertEquals(12f, renderer.textWidth("hi", 9f), "width comes from Font.width");
        assertEquals(9f, renderer.textHeight(9f), "height comes from Font.lineHeight");
    }

    /** 圆角矩形：小半径退化为普通矩形（26.x 没有圆角原语，退化必须干净）。 */
    @Test
    void roundedRectDegeneratesToPlainRectForSmallRadius() {
        ExtractorRenderer renderer = backend();
        FakeExtractor extractor = new FakeExtractor();
        renderer.setFrame(extractor);
        renderer.beginFrame();

        renderer.roundedRect(0f, 0f, 10f, 10f, 0f, Color.WHITE);

        assertEquals(Arrays.asList("fill 0,0,10,10 ffffffff"), extractor.calls);
    }

    /** 圆角矩形：半径大于 0.5 时四角要逐行内缩，画出的第一条指令是圆角行而不是整块。 */
    @Test
    void roundedRectInsetsCornersRowByRow() {
        ExtractorRenderer renderer = backend();
        FakeExtractor extractor = new FakeExtractor();
        renderer.setFrame(extractor);
        renderer.beginFrame();

        renderer.roundedRect(0f, 0f, 20f, 20f, 4f, Color.WHITE);

        // 主体三段 + 四角逐行（半径 4 → 上下各 4 行）。
        assertEquals(3 + 8, extractor.calls.size(), "calls=" + extractor.calls);
        assertTrue(extractor.calls.get(0).startsWith("fill 0,4,20,16"),
                "middle band must be inset vertically by the radius; calls=" + extractor.calls);
    }

    /** 签名不全的绘制上下文不得产出后端：半个后端画不出界面，只会让人误判成"画了但看不见"。 */
    @Test
    void bindRefusesIncompleteExtractors() {
        assertNull(ExtractorRenderer.bind(IncompleteExtractor.class, () -> new FakeMinecraft()));
        assertNull(ExtractorRenderer.bind(null, () -> new FakeMinecraft()));
    }
}
