package dev.nocturne.client.module.modules.render.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 坐标 HUD 的 Horizontal / Vertical 布局。 */
class CoordinatesModuleTest {

    @Test
    void horizontalIsOneLine() {
        assertEquals("XYZ 1.0 / 2.0 / -3.0",
                CoordinatesModule.format(false, 1.0, 2.0, -3.0));
    }

    @Test
    void verticalIsThreeLines() {
        String text = CoordinatesModule.format(true, 1.0, 2.0, -3.0);
        assertEquals("X 1.0\nY 2.0\nZ -3.0", text);
        assertEquals(3, text.split("\n", -1).length);
    }
}
