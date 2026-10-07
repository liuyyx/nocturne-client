package dev.nocturne.ui.gl;

import dev.nocturne.ui.render.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link GlRenderer} 的图元构造契约：矩形/圆角矩形/描边的立即模式调用次数与图元模式，
 * 退化输入的短路行为，文本绘制是否正确走 {@link TextRenderer} 接缝，以及矩阵接管/裁剪开关的
 * <b>有序</b>序列（仅计数会漏掉「glOrtho 跑错矩阵模式」「popClip 不关裁剪」这类缺陷）。
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

    /**
     * 缺少矩阵控制句柄的 GL 替身：其余调用转发给 {@link FakeGl} 以便观察。
     *
     * <p>用于验证「beginFrame 因缺少 glMatrixMode/glOrtho 提前返回」时，
     * {@code endFrame} 仍必须清理已打开的裁剪（C-05：裁剪泄漏与矩阵泄漏是两条独立路径）。
     */
    public static final class FakeGlNoMatrix {
        public static void glColor4f(float r, float g, float b, float a) {
            FakeGl.glColor4f(r, g, b, a);
        }

        public static void glBegin(int mode) {
            FakeGl.glBegin(mode);
        }

        public static void glEnd() {
            FakeGl.glEnd();
        }

        public static void glVertex2f(float x, float y) {
            FakeGl.glVertex2f(x, y);
        }

        public static void glLineWidth(float width) {
            FakeGl.glLineWidth(width);
        }

        public static void glEnable(int cap) {
            FakeGl.glEnable(cap);
        }

        public static void glDisable(int cap) {
            FakeGl.glDisable(cap);
        }

        public static void glGetIntegerv(int target, int[] out) {
            FakeGl.glGetIntegerv(target, out);
        }

        public static void glScissor(int x, int y, int width, int height) {
            FakeGl.glScissor(x, y, width, height);
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
     *
     * <p>断言范围不止次数：{@code glOrtho} 与 {@code glLoadIdentity} 必须发生在
     * {@code GL_PROJECTION} 模式下（L-01：仅记录「最后目标」无法发现模式写错）。
     */
    @Test
    void beginFrameEstablishesOrthographicProjection() {
        FakeGl.viewport = new int[]{0, 0, 1280, 720};
        renderer.beginFrame();

        // 物理 1280x720 → uiScale=2（按基准高度 400 取整）→ 逻辑 640x360。
        // width()/height() 返回的是**逻辑**尺寸：它与 ortho、与所有绘制坐标同源，
        // 输入层的鼠标换算（surface/window）也因此自动跟随，不需要各自处理缩放。
        assertEquals(2, renderer.scale());
        assertEquals(640, renderer.width());
        assertEquals(360, renderer.height());
        // 投影矩阵被压入并重置后切回模型视图，两次切换说明矩阵栈确实被接管
        assertEquals(2, FakeGl.matrixModeCalls);
        assertEquals(GlApi.GL_MODELVIEW, FakeGl.lastMatrixMode);
        assertEquals(1, FakeGl.orthoCalls);
        // 矩阵模式的有序序列必须恰好是 PROJECTION → MODELVIEW
        assertEquals(java.util.Arrays.asList(GlApi.GL_PROJECTION, GlApi.GL_MODELVIEW),
                FakeGl.matrixModeSequence);
        // glLoadIdentity 与 glOrtho 都必须发生在已切到投影矩阵之后
        for (int i : FakeGl.indicesMatchingPrefix("ortho@")) {
            assertEquals(GlApi.GL_PROJECTION, FakeGl.modeOfEvent(FakeGl.events.get(i)),
                    "glOrtho must run under GL_PROJECTION");
        }
        assertTrue(FakeGl.hasEvent("loadIdentity@" + GlApi.GL_PROJECTION),
                "projection matrix must be reset before glOrtho");
        assertTrue(FakeGl.hasEvent("loadIdentity@" + GlApi.GL_MODELVIEW),
                "model view matrix must be reset after switching back");
        // 下边界 = 逻辑高、上边界 = 0：y 轴因此向下增长，与屏幕坐标一致
        assertArrayEquals(new double[]{0d, 640d, 360d, 0d, -1d, 1d}, FakeGl.lastOrtho, 1e-6);

        renderer.endFrame();
        // 还原同样切换两次（投影 + 模型视图），累计四次
        assertEquals(4, FakeGl.matrixModeCalls);
        assertEquals(FakeGl.pushMatrixCalls, FakeGl.popMatrixCalls, "matrix push/pop must balance");
    }

    /**
     * UI 缩放随屏幕高度增长，且小窗保持 1 倍（不缩小）。
     *
     * <p>界面元素都是固定像素尺寸，2K/4K 屏上不缩放就会"十分小"——这条把缩放规则钉住：
     * 逻辑尺寸 = 物理 / uiScale，且 uiScale 只放大不缩小。
     */
    @Test
    void uiScaleGrowsWithResolutionAndNeverShrinks() {
        FakeGl.viewport = new int[]{0, 0, 640, 400};
        renderer.beginFrame();
        assertEquals(1, renderer.scale(), "小窗保持 1 倍");
        assertEquals(640, renderer.width());
        assertEquals(400, renderer.height());
        renderer.endFrame();

        FakeGl.viewport = new int[]{0, 0, 2560, 1440};
        renderer.beginFrame();
        assertEquals(5, renderer.scale(), "2K/1440p 放大 5 倍（基准 320）");
        assertEquals(512, renderer.width());
        assertEquals(288, renderer.height());
        renderer.endFrame();
    }

    /**
     * 视口查询失败（全 0）时不抛异常，也<b>不得</b>调用 {@code glOrtho}：左右/上下相等在 GL 里合法吗？
     * 不合法——规范规定 {@code left == right} 或 {@code bottom == top} 时产生 GL_INVALID_VALUE、
     * 矩阵保持原样，GUI 因而永久画在裁剪空间之外。此时渲染器尺寸保持未知，并打印一次诊断。
     */
    @Test
    void beginFrameToleratesUnknownViewport() {
        FakeGl.viewport = new int[]{0, 0, 0, 0};
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(captured, true, "UTF-8"));
            renderer.beginFrame();
            renderer.endFrame();
            renderer.beginFrame();
            renderer.endFrame();
        } catch (java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        } finally {
            System.setOut(original);
        }

        assertEquals(0, FakeGl.orthoCalls, "0x0 视口下不得发出非法的 glOrtho");
        assertEquals(0, renderer.width());
        assertEquals(FakeGl.pushMatrixCalls, FakeGl.popMatrixCalls,
                "跳过 ortho 时矩阵压栈/弹栈仍必须配平");
        assertEquals(1, countOccurrences(captured.toString(), "cannot read GL_VIEWPORT"),
                "诊断只允许打印一次，避免每帧刷屏");
    }

    /**
     * C-05 回归：上一帧的 {@code endFrame} 未执行时，下一次 {@code beginFrame} 必须先补弹残留矩阵。
     *
     * <p>不补的话矩阵栈每帧净泄漏两层，约 16 帧后游戏的投影矩阵栈永久损坏。这里连续两次
     * beginFrame 只配一次 endFrame，末尾 push/pop 必须配平。
     */
    @Test
    void beginFrameRecoversFromAMissingEndFrame() {
        renderer.beginFrame();
        renderer.beginFrame();   // 模拟上一帧 endFrame 丢失：内部应先 endFrame 再重新压栈
        renderer.endFrame();

        assertEquals(FakeGl.pushMatrixCalls, FakeGl.popMatrixCalls,
                "a missing endFrame must not leak matrix stack entries");
        // 两次 beginFrame 各压入投影 + 模型视图两层，补漏弹 + 显式 endFrame 各弹出两层
        assertEquals(4, FakeGl.pushMatrixCalls);
        assertEquals(4, FakeGl.popMatrixCalls);
    }

    /**
     * C-05 回归：裁剪清理不得被 {@code statePushed} 门控。
     *
     * <p>{@code beginFrame} 会因缺少矩阵句柄提前返回，但 {@code pushClip} 早已打开
     * {@code GL_SCISSOR_TEST}；若 {@code endFrame} 只在压过矩阵时才清理，裁剪区域会逐帧收缩
     * 直至宽高为 0，画面整体消失。
     */
    @Test
    void endFrameClearsScissorEvenWithoutMatrixControl() {
        GlApi bare = GlApi.bind(FakeGlNoMatrix.class);
        assertFalse(bare.hasMatrixControl(), "fixture must lack matrix control");
        GlRenderer lean = new GlRenderer(bare, null);

        lean.beginFrame();                 // 提前返回，未接管矩阵
        assertEquals(0, FakeGl.pushMatrixCalls);

        lean.pushClip(0f, 0f, 10f, 10f);
        assertTrue(FakeGl.isCapEnabled(GlApi.GL_SCISSOR_TEST), "pushClip opens the scissor test");
        assertEquals(1, FakeGl.scissorCalls);

        lean.endFrame();
        assertFalse(FakeGl.isCapEnabled(GlApi.GL_SCISSOR_TEST),
                "endFrame must close a scissor left open by an early-bailing beginFrame");
        assertEquals(1, FakeGl.disableCount(GlApi.GL_SCISSOR_TEST));
    }

    /** pushClip 用 glScissor 实现，且必须翻转 y 轴：GL 的裁剪原点在窗口左下角 */
    @Test
    void clipUsesScissorWithFlippedY() {
        FakeGl.viewport = new int[]{0, 0, 800, 600};
        renderer.beginFrame();
        FakeGl.resetScissorObservations();

        renderer.pushClip(10f, 20f, 100f, 50f);
        assertEquals(1, FakeGl.scissorCalls);
        // GUI 坐标是逻辑坐标（800x600 物理 → uiScale=2 → 逻辑 400x300）：
        // scissor 用的是窗口物理像素，因此先 ×2 得 (20,40,200,100)，
        // GUI 的 y=40..140 在 600 高的物理视口里对应 GL 的 y=460..560。
        assertArrayEquals(new int[]{20, 460, 200, 100}, FakeGl.lastScissor);

        renderer.popClip();
        renderer.endFrame();
    }

    /**
     * L-03 回归：裁剪必须成对地打开/关闭 {@code GL_SCISSOR_TEST}，而不是只下发一次 glScissor。
     *
     * <p>只断言 scissor 的四个参数无法发现「popClip 后裁剪仍处于开启」这一泄漏。
     */
    @Test
    void clipEnablesAndDisablesScissorAroundTheScissorBox() {
        FakeGl.viewport = new int[]{0, 0, 800, 600};
        renderer.beginFrame();
        FakeGl.resetScissorObservations();

        renderer.pushClip(10f, 20f, 100f, 50f);
        assertTrue(FakeGl.isCapEnabled(GlApi.GL_SCISSOR_TEST));
        assertEquals(1, FakeGl.enableCount(GlApi.GL_SCISSOR_TEST));
        // scissor 矩形必须先下发，再打开裁剪测试（坐标同上：逻辑 × uiScale）
        int scissor = FakeGl.indexOf("scissor:20,460,200,100", 0);
        int enable = FakeGl.indexOf("enable:" + GlApi.GL_SCISSOR_TEST, 0);
        assertTrue(scissor >= 0 && enable > scissor, "scissor box must be set before enabling the test");

        renderer.popClip();
        assertFalse(FakeGl.isCapEnabled(GlApi.GL_SCISSOR_TEST), "popClip must close the scissor test");
        assertEquals(1, FakeGl.disableCount(GlApi.GL_SCISSOR_TEST));

        renderer.endFrame();
    }

    /** 统计 {@code needle} 在 {@code haystack} 中出现的次数 */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
