package dev.nocturne.ui.gl;

import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.runtime.InputBlock;
import dev.nocturne.ui.FakeBackend;
import dev.nocturne.ui.FakeInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fabric mod 没有 bootstrap KeySink 时，后续按键仍必须能开关界面。 */
class GuiOverlayInputTest {
    @AfterEach
    void clearInputBlock() {
        InputBlock.setBlocked(false);
        InputBlock.consumePressed(344, Long.MAX_VALUE);
        InputBlock.consumePressed(256, Long.MAX_VALUE);
    }

    @Test
    void recordedToggleStillOpensAfterBootstrapLookupFails() {
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                new FakeBackend(0, 0), new FakeInput(), GuiOverlay.KEY_RIGHT_SHIFT);
        // 第一帧在没有 KeySink 的环境中尝试解析；此时没有按键。
        overlay.onFrame();
        assertFalse(overlay.isOpen());

        // GLFW 轮询仍为 false，仅靠 mod 的输入钩子记录这次按键。
        InputBlock.recordKey(344, 1);
        overlay.onFrame();
        assertTrue(overlay.isOpen(), "bootstrap 不可用后，本地按键事件仍应打开界面");
    }

    @Test
    void recordedEscapeDismissesSearchWithoutPolling() {
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                new FakeBackend(0, 0), new FakeInput(), GuiOverlay.KEY_RIGHT_SHIFT);
        overlay.setOpen(true);
        assertTrue(overlay.gui().keyPressed(KeyEvent.VK_F, 2));
        assertTrue(overlay.gui().keyPressed(KeyEvent.VK_BACK_SPACE, 0), "空搜索框应取得键盘焦点");

        // 只记录事件，不改变 FakeInput 的轮询状态。
        InputBlock.recordKey(256, 1);
        overlay.onFrame();
        assertTrue(overlay.isOpen(), "Esc 应先关闭搜索，而不是关闭整个界面");
        assertFalse(overlay.gui().keyPressed(KeyEvent.VK_BACK_SPACE, 0), "搜索关闭后不应继续接收按键");
    }
}
