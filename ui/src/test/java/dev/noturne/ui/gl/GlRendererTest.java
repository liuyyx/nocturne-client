package dev.noturne.ui.gl;

import dev.noturne.ui.render.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link GlRenderer} 的图元构造契约：矩形/圆角矩形/描边的立即模式调用次数与图元模式，
 * 退化输入的短路行为，以及文本绘制是否正确走 {@link TextRenderer} 接缝（无真实字体）。
 */
class GlRendererTest {

    /** 测试替身：捕获文本绘制调用，使接缝可在没有真实字体的情况下被断言 */
    static final class RecordingText implements TextRenderer {
        /** 已绘制的文本内容列表，按调用顺序累积 */
        final List<String> drawn = new ArrayList<String>();

        @Override
        public void draw(String text, float x, float y, float size, Color color) {
            drawn.add(text);
        }

        @Override
        public float width(String text, float size) {
            return text.length() * size;
        }

        @Override
        public float height(float size) {
            return size;
        }
    }

    /** 每个用例新建的被测渲染器 */
    private GlRenderer renderer;
    /** 与 {@code renderer} 共享的文本接缝替身 */
    private RecordingText text;

    /** 每个用例前重置 {@link FakeGl} 计数并重建渲染器，避免用例间状态泄漏 */
    @BeforeEach
    void setUp() {
        FakeGl.reset();
        text = new RecordingText();
        renderer = new GlRenderer(GlApi.bind(FakeGl.class), text);
    }

    /** 实心矩形发出一次 {@code glBegin}/{@code glEnd} 与四个顶点 */
    @Test
    void rectDrawsOneQuad() {
        renderer.rect(0f, 0f, 10f, 10f, Color.WHITE);
        assertEquals(1, FakeGl.beginCalls);
        assertEquals(4, FakeGl.vertexCalls);
        assertEquals(1, FakeGl.endCalls);
    }

    /** 宽或高为 0、以及颜色为 null 的退化矩形都应被跳过，不产生任何图元 */
    @Test
    void degenerateRectsAreSkipped() {
        renderer.rect(0f, 0f, 0f, 10f, Color.WHITE);
        renderer.rect(0f, 0f, 10f, 0f, Color.WHITE);
        renderer.rect(0f, 0f, 10f, 10f, null);
        assertEquals(0, FakeGl.beginCalls);
    }

    /** 圆角半径为 0 时退化为普通四边形，只发一次图元而非分带绘制 */
    @Test
    void roundedRectFallsBackToAPlainRectWhenRadiusIsTiny() {
        renderer.roundedRect(0f, 0f, 20f, 20f, 0f, Color.WHITE);
        assertEquals(1, FakeGl.beginCalls, "a square corner is just a quad");
        assertEquals(4, FakeGl.vertexCalls);
    }

    /** 正常圆角矩形由 3 条带状四边形加 4 个角的扇形组成，共 7 个独立闭合图元 */
    @Test
    void roundedRectBuildsBandsAndFans() {
        renderer.roundedRect(0f, 0f, 40f, 20f, 4f, Color.WHITE);
        // 3 bands (quads) + 4 corners (fans)
        assertEquals(7, FakeGl.beginCalls);
        assertEquals(7, FakeGl.endCalls, "every band and corner closes its own primitive");
        assertTrue(FakeGl.vertexCalls > 12, "corners add fan vertices");
    }

    /** 描边使用 {@code GL_LINE_LOOP} 图元并发出四个顶点 */
    @Test
    void outlineUsesLineLoop() {
        renderer.outline(0f, 0f, 10f, 10f, 1f, Color.WHITE);
        assertEquals(GlApi.GL_LINE_LOOP, FakeGl.lastBeginMode);
        assertEquals(4, FakeGl.vertexCalls);
    }

    /** 文本经接缝转发且宽度可计算；空字符串被跳过，不新增绘制记录 */
    @Test
    void textGoesThroughTheSeam() {
        renderer.text("hello", 1f, 2f, 14f, Color.WHITE);
        assertEquals(1, text.drawn.size());
        assertEquals("hello", text.drawn.get(0));
        assertEquals(70f, renderer.textWidth("hello", 14f), 1e-3);

        renderer.text("", 0f, 0f, 14f, Color.WHITE);
        assertEquals(1, text.drawn.size(), "empty text is skipped");
    }

    /** 接缝为 null 时不抛异常，文本宽度回退到按字号推算的估算值 */
    @Test
    void nullTextRendererIsTolerated() {
        GlRenderer bare = new GlRenderer(GlApi.bind(FakeGl.class), null);
        bare.text("ignored", 0f, 0f, 12f, Color.WHITE);
        assertEquals(24f, bare.textWidth("abcd", 12f), 1e-3);
    }

    /**
     * beginFrame 接管坐标系：按当前视口建立 y 轴向下的正交投影，并把会遮挡 UI 的 GL 能力关掉。
     *
     * <p>这是固定管线路径能看见东西的前提——帧交换点处的矩阵仍属于游戏，
     * 不接管就会把整个 GUI 送到裁剪空间之外。
     */
    @Test
    void beginFrameEstablishesOrthographicProjection() {
        FakeGl.viewport = new int[]{0, 0, 1280, 720};
        renderer.beginFrame();

        assertEquals(1280, renderer.width());
        assertEquals(720, renderer.height());
        // 投影矩阵被压入并重置后切回模型视图，两次切换说明矩阵栈确实被接管
        assertEquals(2, FakeGl.matrixModeCalls);
        assertEquals(GlApi.GL_MODELVIEW, FakeGl.lastMatrixMode);
        assertEquals(1, FakeGl.orthoCalls);
        // 下边界 = 视口高、上边界 = 0：y 轴因此向下增长，与屏幕坐标一致
        assertArrayEquals(new double[]{0d, 1280d, 720d, 0d, -1d, 1d}, FakeGl.lastOrtho, 1e-6);

        renderer.endFrame();
        // 还原同样切换两次（投影 + 模型视图），累计四次
        assertEquals(4, FakeGl.matrixModeCalls);
    }

    /** 视口查询失败（全 0）时不抛异常，投影边界退化为 0，渲染器尺寸保持未知 */
    @Test
    void beginFrameToleratesUnknownViewport() {
        FakeGl.viewport = new int[]{0, 0, 0, 0};
        renderer.beginFrame();
        assertEquals(1, FakeGl.orthoCalls);
        assertEquals(0, renderer.width());
        renderer.endFrame();
    }

    /** pushClip 用 glScissor 实现，且必须翻转 y 轴：GL 的裁剪原点在窗口左下角 */
    @Test
    void clipUsesScissorWithFlippedY() {
        FakeGl.viewport = new int[]{0, 0, 800, 600};
        renderer.beginFrame();
        FakeGl.scissorCalls = 0;

        renderer.pushClip(10f, 20f, 100f, 50f);
        assertEquals(1, FakeGl.scissorCalls);
        // GUI 的 y=20..70 在 600 高的视口里对应 GL 的 y=530..580
        assertArrayEquals(new int[]{10, 530, 100, 50}, FakeGl.lastScissor);

        renderer.popClip();
        renderer.endFrame();
    }
}
