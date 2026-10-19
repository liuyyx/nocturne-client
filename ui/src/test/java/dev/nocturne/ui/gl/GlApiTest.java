package dev.nocturne.ui.gl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link GlApi} 契约：把任意带 OpenGL 方法的类绑定为可用实例、拒绝不合格类与缺失类，
 * 并确认 {@code fillRect}/{@code strokeRect} 会发出配对的立即模式调用序列。
 */
class GlApiTest {

    /** 每个用例前清空 {@link FakeGl} 的静态计数，保证断言只反映本次绘制 */
    @BeforeEach
    void reset() {
        FakeGl.reset();
    }

    /** 具备所需 GL 方法的类可绑定成功，返回非 null 实例 */
    @Test
    void bindsToAUsableGlClass() {
        GlApi api = GlApi.bind(FakeGl.class);
        assertNotNull(api);
    }

    /** 缺少 GL 方法的类、null 类、按名找不到的类都应绑定失败返回 null，而不是抛异常 */
    @Test
    void rejectsAClassWithoutGlMethods() {
        assertNull(GlApi.bind(Object.class));
        assertNull(GlApi.bind((Class<?>) null));
        assertNull(GlApi.bind("does.not.Exist", getClass().getClassLoader()));
    }

    /**
     * 填充矩形：一次设色 + 四个顶点，**图元留在批次里**（合批，见 eps-plus 的累加器结构），
     * 直到 {@link GlApi#flushBatch()} 才真正 {@code glEnd}。
     */
    @Test
    void fillRectAccumulatesIntoABatch() {
        GlApi api = GlApi.bind(FakeGl.class);
        api.fillRect(10f, 20f, 30f, 40f, 1f, 0f, 0f, 1f);
        api.fillRect(50f, 20f, 30f, 40f, 0f, 1f, 0f, 1f);

        assertEquals(2, FakeGl.colorCalls);
        assertEquals(1, FakeGl.beginCalls, "consecutive quads must share one batch");
        assertEquals(8, FakeGl.vertexCalls);
        assertEquals(0, FakeGl.endCalls, "the batch stays open");
        assertEquals(GlApi.GL_QUADS, FakeGl.lastBeginMode);

        api.flushBatch();
        assertEquals(1, FakeGl.endCalls);
        assertEquals(1, api.takeBatchCounts()[0] == 0 ? 0 : 1);
    }

    /** 批次之间的状态切换必须先结束旧批次（否则状态命令会落进 glBegin/glEnd 之间）。 */
    @Test
    void stateChangeFlushesTheOpenBatch() {
        GlApi api = GlApi.bind(FakeGl.class);
        api.fillRect(0f, 0f, 10f, 10f, 1f, 1f, 1f, 1f);
        api.bindTexture2D(7);
        assertEquals(1, FakeGl.endCalls, "binding a texture must close the pending batch");
        api.flushBatch();
    }

    /** 描边矩形须先设置线宽，并以 {@code GL_LINE_LOOP} 发出四个顶点（同样留在批次里） */
    @Test
    void strokeRectSetsLineWidthAndLoops() {
        GlApi api = GlApi.bind(FakeGl.class);
        api.strokeRect(0f, 0f, 10f, 10f, 2f, 1f, 1f, 1f, 1f);

        assertEquals(1, FakeGl.lineWidthCalls);
        assertEquals(2f, FakeGl.lastLineWidth, 1e-6);
        assertEquals(4, FakeGl.vertexCalls);
        assertEquals(GlApi.GL_LINE_LOOP, FakeGl.lastBeginMode);
        api.flushBatch();
        assertEquals(1, FakeGl.endCalls);
    }

    /** 数组形态存在时按数组形态读视口（LWJGL3） */
    @Test
    void readsViewportThroughTheArrayForm() {
        FakeGl.viewport = new int[]{2, 4, 800, 600};
        int[] viewport = GlApi.bind(FakeGl.class).viewport();

        assertNotNull(viewport);
        assertEquals(2, viewport[0]);
        assertEquals(4, viewport[1]);
        assertEquals(800, viewport[2]);
        assertEquals(600, viewport[3]);
    }

    /**
     * 只有 {@code glGetInteger(int, IntBuffer)} 的类（LWJGL2 / 1.8.9）也必须读得出视口。
     *
     * <p>这是「注入成功但 GUI 全无」的根因回归：读不出视口 → {@code glOrtho(0,0,0,0)} 非法 → 画面
     * 留在裁剪空间之外。此用例在绑定层直接钉住缓冲形态的可用性。
     */
    @Test
    void readsViewportThroughTheLwjgl2BufferForm() {
        FakeGlBufferOnly.reset();
        FakeGlBufferOnly.viewport = new int[]{3, 5, 301, 233};

        GlApi api = GlApi.bind(FakeGlBufferOnly.class);

        assertNotNull(api, "LWJGL2 形态的 GL 类必须能绑定成功");
        int[] viewport = api.viewport();
        assertNotNull(viewport, "缓冲形态可用时不得返回 null");
        assertEquals(3, viewport[0]);
        assertEquals(5, viewport[1]);
        assertEquals(301, viewport[2]);
        assertEquals(233, viewport[3]);
        assertEquals(1, FakeGlBufferOnly.bufferQueryCalls);
        assertTrue(api.hasScissor(), "有 glScissor + 缓冲形态视口查询即应支持裁剪");
    }

    /** 两种视口查询形态都缺失时返回 null（表示「无法查询」），不得伪装成 0x0 视口 */
    @Test
    void viewportIsNullWithoutAnyQueryForm() {
        GlApi api = GlApi.bind(NoViewportGl.class);

        assertNotNull(api);
        assertNull(api.viewport());
        assertFalse(api.hasScissor(), "无法查询视口时裁剪能力视为不可用");
    }

    /** 无任何视口查询形态的 GL 替身；其余句柄齐全，确保绑定本身成功 */
    static final class NoViewportGl {
        public static void glColor4f(float r, float g, float b, float a) {
        }

        public static void glBegin(int mode) {
        }

        public static void glEnd() {
        }

        public static void glVertex2f(float x, float y) {
        }
    }

    /**
     * 状态类调用必须先把打开中的批次收尾。
     *
     * <p>回归：合批之后图集上传发生在批次内部，而 {@code glGenTextures} 落在
     * {@code glBegin/glEnd} 之间是 {@code GL_INVALID_OPERATION}，句柄会一直是 0，
     * 整段文字静默消失（1.8.9 真机实测）。因此凡是非顶点类 GL 调用都必须先 {@code glEnd}。
     */
    @Test
    void stateCallsCloseTheOpenBatchFirst() {
        GlApi api = GlApi.bind(FakeGl.class);
        GlRenderer renderer = new GlRenderer(api, null);

        renderer.rect(0f, 0f, 10f, 10f, dev.nocturne.ui.render.Color.WHITE);
        assertEquals(1, FakeGl.beginCalls, "矩形应打开一个批次");
        assertEquals(0, FakeGl.endCalls, "批次应保持打开（合批）");

        api.genTexture();

        assertEquals(1, FakeGl.endCalls,
                "glGenTextures 之前必须 glEnd，否则 GL_INVALID_OPERATION 让句柄恒为 0");

        renderer.rect(0f, 0f, 10f, 10f, dev.nocturne.ui.render.Color.WHITE);
        api.pushMatrix();
        assertEquals(2, FakeGl.endCalls, "矩阵操作同样不能落在批次内");
    }

    /**
     * 连续两次 {@code beginFan} 必须各自开启一个图元。
     *
     * <p>回归：GL_TRIANGLE_FAN 是一个连续条带，若「同模式合批」把两组互不相连的顶点并进同一个
     * fan，前一组最后一个顶点会与后一组的中心点连成巨大三角面。1.8.9 真机现象是圆角矩形
     * （四个角各一次 beginFan）在半透明/淡入时出现横贯整块的斜三角。
     */
    @Test
    void consecutiveFansAreSeparatePrimitives() {
        GlApi api = GlApi.bind(FakeGl.class);

        api.beginFan(0);
        api.vertex(0f, 0f);
        api.beginFan(0);

        assertEquals(2, FakeGl.beginCalls, "两个扇形必须各自 glBegin");
        assertEquals(1, FakeGl.endCalls, "第二个扇形开始前必须先结束第一个");
    }
}
