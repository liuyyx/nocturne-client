package dev.noturne.ui.gl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.lwjgl.input.Mouse;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ReflectiveInput} 的 LWJGL2 分支测试。
 *
 * <p>重点是 H-25：LWJGL2 在 Windows 上按 {@code WHEEL_DELTA=120} 上报滚轮，必须归一化成
 * 「每格 ±1」才能与 GLFW 同量纲，否则一次滚轮会被 GUI 当成 120 格、内容瞬间飞出屏幕。
 */
class ReflectiveInputTest {

    @BeforeEach
    void setUp() {
        Mouse.x = 0;
        Mouse.y = 0;
        Mouse.dWheel = 0;
        Mouse.grabbed = false;
        Mouse.grabbedCalls = 0;
    }

    /** 类路径上存在 LWJGL2 三件套时，输入后端应解析为 lwjgl2（而非谎报 glfw/none） */
    @Test
    void resolvesTheLwjgl2Backend() {
        InputSource input = ReflectiveInput.create(getClass().getClassLoader(), () -> 800, () -> 600);
        assertEquals("lwjgl2", input.describe());
    }

    /**
     * H-25 回归：Windows 上 120 的滚轮增量必须归一化为 1。
     *
     * <p>非 Windows 平台 LWJGL2 的每格增量本就是 1，除数不同，故本用例只在 Windows 上断言。
     */
    @Test
    void windowsWheelNotchesAreNormalisedToOnePerNotch() {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "WHEEL_DELTA=120 normalisation only applies on Windows");

        InputSource input = ReflectiveInput.create(getClass().getClassLoader(), () -> 800, () -> 600);

        Mouse.dWheel = 120;
        assertEquals(1.0, input.scrollDelta(), 1e-9, "one wheel notch must read as 1, not 120");

        Mouse.dWheel = 240;
        assertEquals(2.0, input.scrollDelta(), 1e-9, "two notches must read as 2");

        Mouse.dWheel = -120;
        assertEquals(-1.0, input.scrollDelta(), 1e-9, "upward notches are negative");

        // 取出即清零：同一格滚动不得在后续帧被重复消费
        assertEquals(0.0, input.scrollDelta(), 1e-9);
    }
}
