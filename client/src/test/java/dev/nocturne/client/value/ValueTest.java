package dev.nocturne.client.value;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置值（Value）的单元测试：验证 {@link BooleanValue} 的切换与重置、
 * {@link NumberValue} 的区间钳制与步长吸附，以及 {@link ModeValue} 的循环与非法取值回退，
 * 并确认「是否为默认值」标记随赋值变化。
 */
class ValueTest {

    /** 验证布尔值：初始即默认值，切换后偏离默认，重置后回到默认且显示串为「Off」。 */
    @Test
    void booleanTogglesAndResets() {
        BooleanValue value = new BooleanValue("Enabled", false);
        assertFalse(value.get());
        assertTrue(value.isDefault());

        value.toggle();
        assertTrue(value.get());
        assertFalse(value.isDefault());

        value.reset();
        assertFalse(value.get());
        assertEquals("Off", value.display());
    }

    /** 验证数值越界时被钳制到 [min, max]，并可安全取整。 */
    @Test
    void numberClampsToRange() {
        NumberValue value = new NumberValue("Reach", 3.0, 3.0, 6.0, 0.0);
        // 构造参数依次为：名称、默认值、下界、上界、步长（0 表示不吸附）。
        value.set(99.0);
        assertEquals(6.0, value.get(), 1e-9);
        value.set(-5.0);
        assertEquals(3.0, value.get(), 1e-9);
        assertEquals(3, value.asInt());
    }

    /** 验证数值被吸附到最近的步长网格上（0.62→0.5，0.9→1.0），避免配置出无意义的细粒度值。 */
    @Test
    void numberSnapsToStep() {
        NumberValue value = new NumberValue("Speed", 1.0, 0.0, 1.0, 0.25);
        value.set(0.62);
        assertEquals(0.5, value.get(), 1e-9);
        value.set(0.9);
        assertEquals(1.0, value.get(), 1e-9);
    }

    /**
     * M-56 回归：步长对齐必须消除二进制误差，否则 0.3 会对齐成 0.30000000000000004，
     * 而 {@code isDefault()} 用 {@code Double.equals} 比较，该设置永远无法判定为默认。
     */
    @Test
    void stepAlignmentIsExactNotBinaryApproximate() {
        NumberValue value = new NumberValue("Fine", 0.3, 0.0, 1.0, 0.1);

        assertTrue(value.isDefault(), "coerced default must equal the declared default bit-for-bit");
        assertEquals(0.3, value.get().doubleValue(), 0.0, "0.3 must stay 0.3 exactly");

        value.set(0.7);
        assertEquals(0.7, value.get().doubleValue(), 0.0, "0.7 must stay 0.7 exactly");
        value.reset();
        assertEquals(0.3, value.get().doubleValue(), 0.0);
        assertTrue(value.isDefault());
    }

    /**
     * 验证模式值：{@code next} 在选项间循环并在末尾回绕，
     * 遇到未知选项时回退到第一个，而不是抛异常或留下非法状态。
     */
    @Test
    void modeCyclesAndRejectsUnknownOptions() {
        ModeValue value = new ModeValue("Mode", "Toggle", "Toggle", "Hold", "Always");
        assertEquals("Toggle", value.get());
        assertEquals(0, value.index());

        value.next();
        assertEquals("Hold", value.get());
        value.next();
        value.next();
        assertEquals("Toggle", value.get(), "must wrap around");

        value.set("Nonsense");
        assertEquals("Toggle", value.get(), "unknown option falls back to the first");
        assertTrue(value.is("Toggle"));
    }

    /** 验证一旦被改写即标记为非默认值，且显示串按整数值格式化。 */
    @Test
    void changingValueMarksItNonDefault() {
        NumberValue value = new NumberValue("Delay", 1.0, 0.0, 5.0, 1.0);
        assertTrue(value.isDefault());
        value.set(3.0);
        assertFalse(value.isDefault());
        assertEquals("3", value.display());
    }
}
