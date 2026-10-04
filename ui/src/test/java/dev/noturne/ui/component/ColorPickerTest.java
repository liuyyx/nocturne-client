package dev.noturne.ui.component;

import dev.noturne.ui.RecordingRenderer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ColorPicker} 的单元测试：点击/拖动换算颜色、拖动目标锁定、
 * 「值未变不回调」约定，以及 {@code setArgb} 的幂等性。
 */
class ColorPickerTest {

    /** 点击色相带只改变色相：暗红初始色点在中段得到同明度的暗青，回调收到新 ARGB。 */
    @Test
    void clickOnHueBarChangesHueOnly() {
        AtomicReference<Integer> reported = new AtomicReference<Integer>();
        ColorPicker picker = new ColorPicker(0xFF800000, reported::set);
        picker.setBounds(0f, 0f, 100f, 15f);

        // 上半条（y < 7.5）为色相带；初始暗红明度为 128/255，必须被保留
        assertTrue(picker.mouseClicked(50, 3, 0));
        int expected = 0xFF000000
                | (java.awt.Color.HSBtoRGB(0.5f, 1f, 128 / 255f) & 0xFFFFFF);
        assertEquals(expected, picker.argb());
        assertEquals(expected, reported.get().intValue());
        assertTrue(picker.mouseReleased(50, 3, 0));
    }

    /** 点击明度带只改变明度：纯红初始色点在 75% 处得到 75% 亮度的红。 */
    @Test
    void clickOnBrightnessBarChangesBrightnessOnly() {
        ColorPicker picker = new ColorPicker(0xFFFF0000, null);
        picker.setBounds(0f, 0f, 100f, 15f);

        // 下半条（y >= 7.5）为明度带
        assertTrue(picker.mouseClicked(75, 12, 0));
        int expected = 0xFF000000 | (java.awt.Color.HSBtoRGB(0f, 1f, 0.75f) & 0xFFFFFF);
        assertEquals(expected, picker.argb());
        assertTrue(picker.mouseReleased(75, 12, 0));
    }

    /** 控件外点击不消费事件、不改值；右键点击同样忽略。 */
    @Test
    void clickOutsideOrWithOtherButtonIsIgnored() {
        ColorPicker picker = new ColorPicker(0xFFFF0000, null);
        picker.setBounds(0f, 0f, 100f, 15f);

        assertFalse(picker.mouseClicked(200, 200, 0));
        assertFalse(picker.mouseClicked(50, 3, 1));
        assertEquals(0xFFFF0000, picker.argb());
    }

    /** 拖动目标在按下时锁定：按在色相带上后即使指针纵向越过明度带，也继续调色相。 */
    @Test
    void dragKeepsAdjustingPressedBar() {
        ColorPicker picker = new ColorPicker(0xFFFF0000, null);
        picker.setBounds(0f, 0f, 100f, 15f);

        picker.mouseClicked(50, 3, 0);            // 按在色相带，hue -> 0.5
        picker.mouseDragged(25, 12, 0, 0, 0);     // 指针已到明度带区域，但仍调色相
        int expected = 0xFF000000 | (java.awt.Color.HSBtoRGB(0.25f, 1f, 1f) & 0xFFFFFF);
        assertEquals(expected, picker.argb());

        // 拖出左边界收敛到 hue = 0
        picker.mouseDragged(-50, 3, 0, 0, 0);
        assertEquals(0xFF000000 | (java.awt.Color.HSBtoRGB(0f, 1f, 1f) & 0xFFFFFF), picker.argb());

        assertTrue(picker.mouseReleased(0, 3, 0));
        assertFalse(picker.mouseDragged(75, 3, 0, 0, 0), "释放后拖动不应再改值");
        assertEquals(0xFF000000 | (java.awt.Color.HSBtoRGB(0f, 1f, 1f) & 0xFFFFFF), picker.argb());
    }

    /** 换算结果与当前颜色相同则不回调；确有变化时每变一次回调一次。 */
    @Test
    void unchangedValueDoesNotCallback() {
        AtomicInteger calls = new AtomicInteger();
        ColorPicker picker = new ColorPicker(0xFFFF0000, v -> calls.incrementAndGet());
        picker.setBounds(0f, 0f, 100f, 15f);

        // 纯红色相恰为 0，点在最左端换算回同一颜色
        picker.mouseClicked(0, 3, 0);
        assertEquals(0, calls.get());
        picker.mouseReleased(0, 3, 0);

        picker.mouseClicked(50, 3, 0);
        assertEquals(1, calls.get());
        picker.mouseReleased(50, 3, 0);

        // 同一位置再次点击，换算结果不变，不重复回调
        picker.mouseClicked(50, 3, 0);
        assertEquals(1, calls.get());
        picker.mouseReleased(50, 3, 0);
    }

    /** setArgb：值不变不回调，值变化回调一次；alpha 参与比较且在后续交互中被保留。 */
    @Test
    void setArgbIsIdempotentAndPreservesAlpha() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Integer> last = new AtomicReference<Integer>();
        ColorPicker picker = new ColorPicker(0xFFFF0000, v -> {
            calls.incrementAndGet();
            last.set(v);
        });
        picker.setBounds(0f, 0f, 100f, 15f);

        picker.setArgb(0xFFFF0000);
        assertEquals(0, calls.get(), "与当前值相同不得回调");

        picker.setArgb(0xFF00FF00);
        assertEquals(1, calls.get());
        assertEquals(0xFF00FF00, last.get().intValue());
        assertEquals(0xFF00FF00, picker.argb());

        // 仅 alpha 不同也算一次变化
        picker.setArgb(0x8000FF00);
        assertEquals(2, calls.get());

        // 交互合成新颜色时保留既有 alpha
        picker.mouseClicked(50, 12, 0);
        picker.mouseReleased(50, 12, 0);
        assertEquals(0x80000000, picker.argb() & 0xFF000000);
    }

    /** 渲染只使用矩形原语（分段渐变 + 指示标记）；不可见时不绘制任何内容。 */
    @Test
    void renderUsesOnlyRectPrimitives() {
        ColorPicker picker = new ColorPicker(0xFFFF0000, null);
        picker.setBounds(0f, 0f, 100f, 15f);

        RecordingRenderer renderer = new RecordingRenderer();
        picker.render(renderer);
        assertTrue(renderer.count("rect") > 0, "渐变带与指示标记必须画出");
        assertEquals(0, renderer.count("roundedRect"));
        assertEquals(0, renderer.count("outline"));

        picker.setVisible(false);
        renderer.calls.clear();
        picker.render(renderer);
        assertTrue(renderer.calls.isEmpty(), "不可见时不得产生任何绘制调用");
    }
}
