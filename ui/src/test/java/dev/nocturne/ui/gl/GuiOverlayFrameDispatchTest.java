package dev.nocturne.ui.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖层绘制闸门回归：GUI、世界覆盖层、文本 HUD、复合 HUD 元素、整帧后处理任一存在都必须绘制。 */
class GuiOverlayFrameDispatchTest {

    @Test
    void closedGuiWithEnabledWorldOverlayStillDraws() {
        assertTrue(GuiOverlay.needsFrame(false, true, false, false, false),
                "有启用中的 WorldOverlay 时，即使 GUI 关闭、hudLines 为空也必须绘制");
    }

    @Test
    void closedGuiWithHudLinesStillDraws() {
        assertTrue(GuiOverlay.needsFrame(false, false, true, false, false));
    }

    @Test
    void closedGuiWithHudElementsStillDraws() {
        assertTrue(GuiOverlay.needsFrame(false, false, false, true, false));
    }

    @Test
    void openGuiAlwaysDraws() {
        assertTrue(GuiOverlay.needsFrame(true, false, false, false, false));
    }

    @Test
    void idleFrameSkips() {
        assertFalse(GuiOverlay.needsFrame(false, false, false, false, false),
                "没有 GUI、覆盖层、任一种 HUD 内容和整帧后处理时才允许跳过整帧");
    }

    @Test
    void closedGuiWithFrameEffectsStillDraws() {
        assertTrue(GuiOverlay.needsFrame(false, false, false, false, true),
                "Motion Blur / Anti Alias 单开且无其它覆盖内容时也必须进 beginFrame");
    }
}
