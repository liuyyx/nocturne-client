package dev.nocturne.ui.gl;

import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.ui.FakeBackend;
import dev.nocturne.ui.FakeInput;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关界面/panic 时指针抓取的恢复契约。
 *
 * <p>真机（1.8.9 panic、GUI 开着）曾出现“视角永久失效”：还原只在关闭帧尝试一次，
 * 游戏晚一帧才接受抓取时就再也没有重试机会。这里钉住两件事：
 * 抓取瞬时失败后后续帧必须重试并最终成功；panic 收尾必须清掉过期的界面/指针簿记。
 */
class GuiOverlayPointerRestoreTest {

    /** 抓取会“瞬时失败”的输入源：前 {@code failures} 次 grab 不改变状态。 */
    private static final class FlakyInput implements InputSource {
        final FakeInput delegate = new FakeInput();
        int failures;
        int grabCalls;

        FlakyInput(int failures, boolean initiallyGrabbed) {
            this.failures = failures;
            delegate.pointerGrabbed = initiallyGrabbed;
        }

        @Override
        public double mouseX() {
            return delegate.mouseX();
        }

        @Override
        public double mouseY() {
            return delegate.mouseY();
        }

        @Override
        public boolean mouseDown(int button) {
            return delegate.mouseDown(button);
        }

        @Override
        public double scrollDelta() {
            return delegate.scrollDelta();
        }

        @Override
        public boolean keyDown(int key) {
            return delegate.keyDown(key);
        }

        @Override
        public void setPointerGrabbed(boolean grabbed) {
            delegate.pointerGrabCalls++;
            if (grabbed) {
                grabCalls++;
                if (failures > 0) {
                    failures--;
                    delegate.pointerGrabbed = false;
                    return;
                }
            }
            delegate.pointerGrabbed = grabbed;
        }

        @Override
        public boolean isPointerGrabbed() {
            return delegate.pointerGrabbed;
        }

        @Override
        public String describe() {
            return "flaky";
        }
    }

    @Test
    void pointerGrabRetriesOnFollowingFrames() {
        FlakyInput input = new FlakyInput(1, true);
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                new FakeBackend(0, 0), input, GuiOverlay.KEY_RIGHT_SHIFT);

        overlay.onFrame();
        overlay.setOpen(true);
        overlay.onFrame();
        assertFalse(input.isPointerGrabbed(), "opening the GUI must release the pointer");

        overlay.setOpen(false);
        overlay.onFrame();
        assertFalse(input.isPointerGrabbed(), "first grab attempt is transiently rejected");

        overlay.onFrame();
        assertTrue(input.isPointerGrabbed(), "the retry on the following frame must take effect");
        assertTrue(input.grabCalls >= 2, "restore must retry after the first failure");
    }

    @Test
    void restoreInputStateRetriesWithinTheAction() {
        // panic 已经把帧分发器 kill 掉，onFrame 不会再跑：重试必须在还原动作内部完成。
        FlakyInput input = new FlakyInput(1, true);
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                new FakeBackend(0, 0), input, GuiOverlay.KEY_RIGHT_SHIFT);
        overlay.onFrame();
        overlay.setOpen(true);
        overlay.onFrame();

        overlay.restoreInputState();

        assertTrue(input.isPointerGrabbed(),
                "the restore action itself must retry the transient grab failure");
        assertTrue(input.grabCalls >= 2, "first grab is rejected, second must be attempted");
    }

    @Test
    void restoreInputStateClearsStaleBookkeeping() throws Exception {
        FlakyInput input = new FlakyInput(0, true);
        GuiOverlay overlay = new GuiOverlay(new ModuleRegistry(),
                new FakeBackend(0, 0), input, GuiOverlay.KEY_RIGHT_SHIFT);
        overlay.onFrame();
        overlay.setOpen(true);
        overlay.onFrame();

        overlay.restoreInputState();

        assertFalse(readBoolean(overlay, "wasOpenForPointer"),
                "panic restore must clear the open bookkeeping");
        assertFalse(readBoolean(overlay, "pointerRestorePending"),
                "successful panic restore must not leave a pending retry");
        assertFalse(overlay.isOpen(), "panic restore must close the overlay");
        assertTrue(input.isPointerGrabbed(), "panic restore must hand the pointer back to the game");
    }

    private static boolean readBoolean(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }
}
