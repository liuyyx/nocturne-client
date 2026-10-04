package dev.noturne.ui.gl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    /** 填充实心矩形恰好发出一次颜色设置、一次 {@code glBegin(GL_QUADS)}、四个顶点和一次 {@code glEnd} */
    @Test
    void fillRectEmitsOneColorAndFourVertices() {
        GlApi api = GlApi.bind(FakeGl.class);
        api.fillRect(10f, 20f, 30f, 40f, 1f, 0f, 0f, 1f);

        assertEquals(1, FakeGl.colorCalls);
        assertEquals(1, FakeGl.beginCalls);
        assertEquals(4, FakeGl.vertexCalls);
        assertEquals(1, FakeGl.endCalls);
        assertEquals(GlApi.GL_QUADS, FakeGl.lastBeginMode);
    }

    /** 描边矩形须先设置线宽，并以 {@code GL_LINE_LOOP} 发出四个顶点 */
    @Test
    void strokeRectSetsLineWidthAndLoops() {
        GlApi api = GlApi.bind(FakeGl.class);
        api.strokeRect(0f, 0f, 10f, 10f, 2f, 1f, 1f, 1f, 1f);

        assertEquals(1, FakeGl.lineWidthCalls);
        assertEquals(2f, FakeGl.lastLineWidth, 1e-6);
        assertEquals(4, FakeGl.vertexCalls);
        assertEquals(GlApi.GL_LINE_LOOP, FakeGl.lastBeginMode);
    }
}
