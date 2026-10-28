package dev.nocturne.ui.gl;

import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.runtime.InputBlock;
import dev.nocturne.ui.FakeBackend;
import dev.nocturne.ui.FakeInput;
import dev.nocturne.ui.render.Renderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void nativeScreenClickIsSuppressedUntilRelease() throws Exception {
        FakeInput input = new FakeInput();
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                new FakeBackend(0, 0), input, GuiOverlay.KEY_RIGHT_SHIFT);
        RecordingGui recording = new RecordingGui();
        Field gui = GuiOverlay.class.getDeclaredField("gui");
        gui.setAccessible(true);
        gui.set(overlay, recording);
        overlay.setOpen(true);
        Field handoff = GuiOverlay.class.getDeclaredField("nativeMouseHandoff");
        handoff.setAccessible(true);
        handoff.setBoolean(overlay, true);

        input.buttons.add(0);
        overlay.onFrame();
        input.x = 20;
        overlay.onFrame();
        assertEquals(0, recording.clicks, "原生返回按钮的按下不能变成 GUI 新点击");
        assertEquals(0, recording.drags, "按住期间的移动也不能穿透成拖拽");

        input.buttons.clear();
        overlay.onFrame();
        assertEquals(0, recording.releases, "不能补发没有对应 GUI 按下的释放");

        input.buttons.add(0);
        overlay.onFrame();
        assertEquals(1, recording.clicks, "松开后的全新点击必须恢复");
    }

    /** 只记录 OverlayGui 收到的事件，避免把点击坐标和实际模块布局耦合进交接测试。 */
    private static final class RecordingGui implements OverlayGui {
        private boolean open;
        private int clicks;
        private int drags;
        private int releases;

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void setOpen(boolean open) {
            this.open = open;
        }

        @Override
        public void toggle() {
            open = !open;
        }

        @Override
        public void setViewport(int width, int height) {
        }

        @Override
        public void update(long nowMs, double mouseX, double mouseY) {
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            clicks++;
            return true;
        }

        @Override
        public boolean mouseReleased(double mx, double my, int button) {
            releases++;
            return true;
        }

        @Override
        public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
            drags++;
            return true;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int modifiers) {
            return true;
        }

        @Override
        public boolean charTyped(char c) {
            return true;
        }

        @Override
        public void cancelInteractions() {
        }

        @Override
        public void render(Renderer renderer) {
        }
    }
}
