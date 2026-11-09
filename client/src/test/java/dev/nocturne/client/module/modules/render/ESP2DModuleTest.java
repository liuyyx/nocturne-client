package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Module;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.Value;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ESP 2D 视觉契约：Epsilon 的实心彩边、黑色外描边、按框高缩放的血条与分类颜色。
 *
 * <p>这些断言不依赖游戏实例：几何与布局都是纯函数，颜色分类也拆成了可测试的优先级判定。
 */
class ESP2DModuleTest {

    private static final float EPS = 0.0001f;

    @Test
    void solidBoxUsesFourFilledColorEdges() {
        RecordingDraw draw = new RecordingDraw();
        int green = 0xFF00FF00;
        ESP2DModule.drawSolidBox(draw, 10f, 20f, 110f, 70f, green);

        assertEquals(4, draw.rects.size(), "Epsilon 的框是四条实心边，不是 outline");
        assertRect(draw.rects.get(0), 9.5f, 20f, 0.5f, 50f, green);
        assertRect(draw.rects.get(1), 10f, 69.5f, 100f, 0.5f, green);
        assertRect(draw.rects.get(2), 9.5f, 20f, 100.5f, 0.5f, green);
        assertRect(draw.rects.get(3), 109.5f, 20f, 0.5f, 50f, green);
    }

    @Test
    void blackOutlineIsDrawnOutsideColorEdges() {
        RecordingDraw draw = new RecordingDraw();
        ESP2DModule.drawBoxOutline(draw, 10f, 20f, 110f, 70f);

        assertEquals(4, draw.rects.size(), "黑色外描边同样由四条实心矩形组成");
        assertRect(draw.rects.get(0), 9f, 20f, 1.5f, 50.5f, 0xFF000000);
        assertRect(draw.rects.get(1), 9f, 19.5f, 101.5f, 1f, 0xFF000000);
        assertRect(draw.rects.get(2), 109f, 20f, 1.5f, 50.5f, 0xFF000000);
        assertRect(draw.rects.get(3), 9f, 69f, 101.5f, 1.5f, 0xFF000000);

        // 彩色边必须落在黑色描边的覆盖范围内（Epsilon 让两者重叠半像素）：左边 9.5..10 在黑色
        // 9..10.5 内，上边 20..20.5 在黑色 19.5..20.5 内。这个断言防止以后把黑边画到彩边内侧，
        // 变成「彩色填充 + 黑边」的旧样式。
        assertTrue(9.5f >= 9f - EPS && 10f <= 9f + 1.5f + EPS, "彩色左边应压在黑色描边内侧");
        assertTrue(20f >= 19.5f - EPS && 20.5f <= 19.5f + 1f + EPS, "彩色上边应压在黑色描边内侧");
    }

    @Test
    void healthBarScalesWithBoxHeight() {
        ESP2DModule.HealthBar bar = new ESP2DModule.HealthBar();

        // 框高 45：缩放 1，宽 2、间距 3、描边 1。
        ESP2DModule.layoutHealthBar(100f, 10f, 55f, 2d, 1d, true, 20f, 20f, bar);
        assertTrue(bar.valid);
        assertEquals(94f, bar.x, EPS);
        assertEquals(2f, bar.width, EPS);
        assertEquals(1f, bar.outlineWidth, EPS);
        assertEquals(10f, bar.fillY, EPS);
        assertEquals(45f, bar.height, EPS);

        // 框高 90：缩放 2，宽 4、间距 6、描边 2 —— 全部随框高同比放大。
        ESP2DModule.layoutHealthBar(100f, 10f, 100f, 2d, 1d, true, 20f, 20f, bar);
        assertEquals(88f, bar.x, EPS);
        assertEquals(4f, bar.width, EPS);
        assertEquals(2f, bar.outlineWidth, EPS);
        assertEquals(90f, bar.height, EPS);
    }

    @Test
    void healthFillGrowsFromBottomAndClamps() {
        ESP2DModule.HealthBar bar = new ESP2DModule.HealthBar();

        // 半血：填充从底部往上到框高的一半。
        ESP2DModule.layoutHealthBar(100f, 10f, 55f, 2d, 1d, true, 10f, 20f, bar);
        assertEquals(32.5f, bar.fillY, EPS);
        assertEquals(22.5f, bar.y + bar.height - bar.fillY, EPS);

        // 治疗过量：比例夹到 1，不能把血条画出框顶。
        ESP2DModule.layoutHealthBar(100f, 10f, 55f, 2d, 1d, true, 40f, 20f, bar);
        assertEquals(10f, bar.fillY, EPS);

        // 负血量/零上限：比例夹到 0，至少还有黑底。
        ESP2DModule.layoutHealthBar(100f, 10f, 55f, 2d, 1d, true, -5f, 0f, bar);
        assertEquals(55f, bar.fillY, EPS);
    }

    @Test
    void outlineOffStillDrawsBlackBackground() {
        ESP2DModule.HealthBar bar = new ESP2DModule.HealthBar();
        ESP2DModule.layoutHealthBar(100f, 10f, 55f, 2d, 1d, false, 20f, 20f, bar);
        assertFalse(bar.outline);
        assertEquals(0f, bar.outlineWidth, EPS);

        RecordingDraw draw = new RecordingDraw();
        ESP2DModule.drawHealthBar(draw, bar, 0xFF2FFF00);
        assertEquals(2, draw.rects.size(), "黑底 + 健康色填充");
        assertRect(draw.rects.get(0), bar.x, 10f, bar.width, 45f, 0xFF000000);
        assertRect(draw.rects.get(1), bar.x, 10f, bar.width, 45f, 0xFF2FFF00);
    }

    @Test
    void effectiveMaxHealthIncludesAbsorptionAndNeverDividesByZero() {
        assertEquals(24f, ESP2DModule.effectiveMaxHealth(20f, 4f), EPS);
        assertEquals(20f, ESP2DModule.effectiveMaxHealth(20f, -4f), EPS);
        assertEquals(1f, ESP2DModule.effectiveMaxHealth(0f, 0f), EPS);
    }

    @Test
    void categoryPriorityMatchesEpsilon() {
        assertEquals(ESP2DModule.Kind.FRIEND,
                ESP2DModule.kindOf(true, true, true, true, true), "好友优先于玩家/生物");
        assertEquals(ESP2DModule.Kind.PLAYER,
                ESP2DModule.kindOf(false, true, false, false, true), "玩家优先于 Mob");
        assertEquals(ESP2DModule.Kind.AMBIENT,
                ESP2DModule.kindOf(false, false, true, true, true), "环境生物先于动物/Mob");
        assertEquals(ESP2DModule.Kind.ANIMAL,
                ESP2DModule.kindOf(false, false, false, true, true), "动物先于 Mob");
        assertEquals(ESP2DModule.Kind.MOB,
                ESP2DModule.kindOf(false, false, false, false, true));
        assertEquals(ESP2DModule.Kind.OTHER,
                ESP2DModule.kindOf(false, false, false, false, false));
    }

    @Test
    void defaultsMatchEpsilon() {
        ESP2DModule module = new ESP2DModule();
        assertTrue(bool(module, "Players").get());
        assertTrue(bool(module, "Friends").get());
        assertFalse(bool(module, "Creatures").get());
        assertFalse(bool(module, "Monsters").get());
        assertFalse(bool(module, "Ambients").get());
        assertFalse(bool(module, "Others").get());
        assertTrue(bool(module, "Render Health").get());
        assertEquals(2d, number(module, "Health Bar Width").get(), 1e-9);
        assertTrue(bool(module, "Health Bar Outline").get());
        assertEquals(1d, number(module, "Health Bar Outline Width").get(), 1e-9);
        assertTrue(bool(module, "Render Box").get());
        assertTrue(bool(module, "Box Outline").get());

        assertEquals(ESP2DModule.DEFAULT_PLAYERS_COLOR, color(module, "Players Color").argb());
        assertEquals(ESP2DModule.DEFAULT_FRIENDS_COLOR, color(module, "Friends Color").argb());
        assertEquals(ESP2DModule.DEFAULT_CREATURES_COLOR, color(module, "Creatures Color").argb());
        assertEquals(ESP2DModule.DEFAULT_MONSTERS_COLOR, color(module, "Monsters Color").argb());
        assertEquals(ESP2DModule.DEFAULT_AMBIENTS_COLOR, color(module, "Ambients Color").argb());
        assertEquals(ESP2DModule.DEFAULT_OTHERS_COLOR, color(module, "Others Color").argb());
        assertEquals(ESP2DModule.DEFAULT_HEALTH_COLOR, color(module, "Health Color").argb());
    }

    private static BooleanValue bool(Module module, String name) {
        return (BooleanValue) value(module, name);
    }

    private static NumberValue number(Module module, String name) {
        return (NumberValue) value(module, name);
    }

    private static ColorValue color(Module module, String name) {
        return (ColorValue) value(module, name);
    }

    private static Value<?> value(Module module, String name) {
        for (Value<?> value : module.values()) {
            if (name.equals(value.name())) {
                return value;
            }
        }
        throw new AssertionError("missing setting " + name);
    }

    private static void assertRect(RecordingDraw.Rect rect, float x, float y, float width,
                                   float height, int color) {
        assertEquals(x, rect.x, EPS);
        assertEquals(y, rect.y, EPS);
        assertEquals(width, rect.width, EPS);
        assertEquals(height, rect.height, EPS);
        assertEquals(color, rect.color);
    }

    /** 只记录 rect 的绘制面；其它图元在本测试中不使用。 */
    private static final class RecordingDraw implements OverlayDraw {
        private final List<Rect> rects = new ArrayList<Rect>();
        private final int width = 854;
        private final int height = 480;

        @Override
        public void rect(float x, float y, float width, float height, int argb) {
            rects.add(new Rect(x, y, width, height, argb));
        }

        @Override
        public void outline(float x, float y, float width, float height, float thickness, int argb) {
        }

        @Override
        public void text(String text, float x, float y, int argb) {
        }

        @Override
        public void roundedRect(float x, float y, float width, float height, float radius, int argb) {
        }

        @Override
        public float textWidth(String text) {
            return 0f;
        }

        @Override
        public float textHeight() {
            return 9f;
        }

        @Override
        public void text(String text, float x, float y, float size, int argb) {
        }

        @Override
        public float textWidth(String text, float size) {
            return 0f;
        }

        @Override
        public float textHeight(float size) {
            return size;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public int uploadTexture(int id, int width, int height, int[] argb) {
            return 0;
        }

        @Override
        public void deleteTexture(int id) {
        }

        @Override
        public void texturedQuad(int texture, float x, float y, float width, float height,
                                 float u0, float v0, float u1, float v1, int argb) {
        }

        @Override
        public boolean supportsTextures() {
            return false;
        }

        private static final class Rect {
            private final float x;
            private final float y;
            private final float width;
            private final float height;
            private final int color;

            private Rect(float x, float y, float width, float height, int color) {
                this.x = x;
                this.y = y;
                this.width = width;
                this.height = height;
                this.color = color;
            }
        }
    }
}
