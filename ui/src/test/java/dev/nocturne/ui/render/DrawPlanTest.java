package dev.nocturne.ui.render;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DrawPlan} 的几何降级与提交重写规则。
 *
 * <p>这些断言钉住的是「上层写一份、后端只认三原语」这条契约：圆角必须变成确定的填充序列，
 * 提交前必须剔除裁剪外命令并合并相邻同色矩形。
 */
final class DrawPlanTest {

    /** 记录 {@link DrawSink} 收到的原始调用。 */
    private static final class RecordingSink implements DrawSink {
        final List<float[]> fills = new ArrayList<>();
        final List<float[]> scissors = new ArrayList<>();
        int blits;

        @Override
        public void fill(float x1, float y1, float x2, float y2, Color color) {
            fills.add(new float[]{x1, y1, x2, y2, color.packed()});
        }

        @Override
        public void blit(int texture, float x, float y, float width, float height,
                         float u0, float v0, float u1, float v1, Color tint) {
            blits++;
        }

        @Override
        public void scissor(float x, float y, float width, float height) {
            scissors.add(new float[]{x, y, width, height});
        }
    }

    private static final Color RED = Color.argb(255, 255, 0, 0);

    @Test
    void zeroRadiusDegeneratesToSingleRect() {
        DrawPlan plan = new DrawPlan(null);
        plan.begin();
        plan.roundedRect(10f, 10f, 100f, 40f, 0f, RED);

        RecordingSink sink = new RecordingSink();
        plan.flush(sink);

        assertEquals(1, sink.fills.size(), "半径 0 应退化为一条填充");
        assertEquals(10f, sink.fills.get(0)[0], 0.001f);
        assertEquals(110f, sink.fills.get(0)[2], 0.001f);
    }

    @Test
    void roundedRectEmitsCenterAndFourCorners() {
        DrawPlan plan = new DrawPlan(null);
        plan.begin();
        plan.roundedRect(0f, 0f, 100f, 40f, 8f, RED);

        RecordingSink sink = new RecordingSink();
        plan.flush(sink);

        // 中央 1 条 + 上下各 12 条；相邻段同色同高时可能被合并，这里只断言存在水平带。
        assertTrue(sink.fills.size() >= 1, "至少一条填充");
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (float[] f : sink.fills) {
            minY = Math.min(minY, f[1]);
            maxY = Math.max(maxY, f[3]);
        }
        assertEquals(0f, minY, 0.001f, "圆角应从 y 开始");
        assertEquals(40f, maxY, 0.001f, "圆角应到 y+height 结束");
    }

    @Test
    void outlineEmitsFourFills() {
        DrawPlan plan = new DrawPlan(null);
        plan.begin();
        plan.outline(0f, 0f, 100f, 50f, 2f, RED);

        RecordingSink sink = new RecordingSink();
        plan.flush(sink);

        assertEquals(4, sink.fills.size(), "描边 = 上/下/左/右四条填充");
    }

    @Test
    void transparentAndEmptyRectanglesAreDropped() {
        DrawPlan plan = new DrawPlan(null);
        plan.begin();
        plan.rect(0f, 0f, 10f, 10f, Color.TRANSPARENT);
        plan.rect(0f, 0f, 0f, 10f, RED);

        RecordingSink sink = new RecordingSink();
        plan.flush(sink);

        assertEquals(0, sink.fills.size());
    }

    @Test
    void cullsCommandsOutsideClip() {
        DrawPlan plan = new DrawPlan(null);
        plan.begin();
        plan.pushClip(0f, 0f, 10f, 10f);
        plan.rect(0f, 0f, 5f, 5f, RED);
        plan.rect(100f, 100f, 5f, 5f, RED);

        RecordingSink sink = new RecordingSink();
        plan.flush(sink);

        assertEquals(1, sink.scissors.size(), "pushClip 产生一条裁剪命令");
        assertEquals(1, sink.fills.size(), "裁剪外的矩形应被剔除");
    }

    @Test
    void mergesAdjacentSameColorRectangles() {
        DrawPlan plan = new DrawPlan(null);
        plan.begin();
        plan.rect(0f, 0f, 10f, 10f, RED);
        plan.rect(10f, 0f, 10f, 10f, RED);

        RecordingSink sink = new RecordingSink();
        plan.flush(sink);

        assertEquals(1, sink.fills.size(), "相邻同色同高矩形应合并");
        assertEquals(0f, sink.fills.get(0)[0], 0.001f);
        assertEquals(20f, sink.fills.get(0)[2], 0.001f);
    }

    @Test
    void nestedClipIntersectsParent() {
        DrawPlan plan = new DrawPlan(null);
        plan.begin();
        plan.pushClip(0f, 0f, 100f, 100f);
        plan.pushClip(50f, 50f, 100f, 100f);

        RecordingSink sink = new RecordingSink();
        plan.flush(sink);

        assertEquals(2, sink.scissors.size());
        float[] inner = sink.scissors.get(1);
        assertEquals(50f, inner[0], 0.001f);
        assertEquals(50f, inner[1], 0.001f);
        assertEquals(50f, inner[2], 0.001f, "子裁剪应与父裁剪求交");
        assertEquals(50f, inner[3], 0.001f);
    }

    @Test
    void popClipRestoresParentScissor() {
        DrawPlan plan = new DrawPlan(null);
        plan.begin();
        plan.pushClip(0f, 0f, 100f, 100f);
        plan.pushClip(10f, 10f, 20f, 20f);
        plan.popClip();

        RecordingSink sink = new RecordingSink();
        plan.flush(sink);

        assertEquals(3, sink.scissors.size(), "push/push/pop 各一条");
        float[] restored = sink.scissors.get(2);
        assertEquals(0f, restored[0], 0.001f);
        assertEquals(100f, restored[2], 0.001f, "pop 后应恢复父裁剪");
    }
}
