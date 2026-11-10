package dev.nocturne.ui.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖层绘制闸门回归：GUI 关闭、hudLines 为空时，只要有一个启用中的世界覆盖层，
 * 也必须进入 beginFrame + drawWorldOverlays + endFrame。
 *
 * <p>1.8.9 真机问题：ESP 2D 已启用，但 drawFrame 的早退条件把「界面关闭 + 无 HUD 行」
 * 当成无事可做，世界覆盖层一帧都没跑；用户只能看到 World3D 自检层的绿框白环。
 */
class GuiOverlayFrameDispatchTest {

    @Test
    void closedGuiWithEnabledWorldOverlayStillDraws() {
        assertTrue(GuiOverlay.needsFrame(false, true, false),
                "有启用中的 WorldOverlay 时，即使 GUI 关闭、hudLines 为空也必须绘制");
    }

    @Test
    void closedGuiWithHudLinesStillDraws() {
        assertTrue(GuiOverlay.needsFrame(false, false, true));
    }

    @Test
    void openGuiAlwaysDraws() {
        assertTrue(GuiOverlay.needsFrame(true, false, false));
    }

    @Test
    void idleFrameSkips() {
        assertFalse(GuiOverlay.needsFrame(false, false, false),
                "没有 GUI、覆盖层和 HUD 行时才允许跳过整帧");
    }
}
