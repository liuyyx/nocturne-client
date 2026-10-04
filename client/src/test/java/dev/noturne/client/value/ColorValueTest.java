package dev.noturne.client.value;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ColorValue} 的单元测试：{@code display} 固定输出忽略 alpha 的 {@code #RRGGBB}
 * 大写六位十六进制，{@code coerce} 对任意 int 原样接受（颜色没有非法值）。
 */
class ColorValueTest {

    /** display 忽略 alpha，输出大写、固定六位、不足补零的 {@code #RRGGBB}。 */
    @Test
    void displayIgnoresAlphaAndPadsToSixDigits() {
        ColorValue value = new ColorValue("Accent", 0xFF7A5CFF);
        assertEquals("#7A5CFF", value.display());

        // alpha 为 0 只影响打包值，不影响显示
        value.set(0x00123456);
        assertEquals("#123456", value.display());

        // 有效数字不足六位时左侧补零
        value.set(0xFF00000A);
        assertEquals("#00000A", value.display());
    }

    /** coerce 原样接受任意 int（含透明色与负数打包值），不做钳制或回退。 */
    @Test
    void coerceAcceptsAnyIntVerbatim() {
        ColorValue value = new ColorValue("Color", 0xFF112233);

        value.set(0x00ABCDEF);
        assertEquals(0x00ABCDEF, value.get().intValue());

        value.set(0x80FF0000);
        assertEquals(0x80FF0000, value.get().intValue());

        value.set(0xFFFFFFFF);
        assertEquals(0xFFFFFFFF, value.get().intValue());
    }

    /** 构造后即为默认值；改写后标记为非默认，{@code reset} 回到默认。 */
    @Test
    void defaultTrackingAndReset() {
        ColorValue value = new ColorValue("Color", 0xFF112233);
        assertTrue(value.isDefault());
        assertEquals(0xFF112233, value.argb());

        value.set(0xFF000000);
        assertFalse(value.isDefault());

        value.reset();
        assertTrue(value.isDefault());
        assertEquals("#112233", value.display());
    }

    /**
     * L-19 回归：{@code display()} 必须与默认 Locale 无关。
     *
     * <p>在 de_DE / ar-EG 等区域下，十六进制输出仍须是大写 ASCII，否则 CI 换 Locale 就会漂移。
     */
    @Test
    void displayIsLocaleIndependent() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("de-DE"));
            ColorValue value = new ColorValue("Accent", 0xFF7A5CFF);
            assertEquals("#7A5CFF", value.display());

            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"));
            assertEquals("#7A5CFF", value.display());

            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fr-FR"));
            value.set(0xFF00000A);
            assertEquals("#00000A", value.display());
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
