package dev.nocturne.agent;

import dev.nocturne.client.input.MouseButtons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SDL 鼠标按钮归一（26.3 {@code MouseHandler#onButton} → {@code MouseButtonEvent} 契约）。
 *
 * <p>SDL 原生编号是 1=左、2=中、3=右；契约是 0=左、1=右、2=中。26.3 真机事故：
 * 左键未归一时被当成右键，RightClicker 在只按左键的情况下自动右键。
 */
class MouseButtonSdlTest {

    @Test
    void sdlLeftAndRightMapToTheContract() {
        assertEquals(0, MouseButtons.fromSdl(1), "SDL 左键 1 必须归一为契约 0");
        assertEquals(1, MouseButtons.fromSdl(3), "SDL 右键 3 必须归一为契约 1");
        assertEquals(2, MouseButtons.fromSdl(2), "SDL 中键 2 在契约里仍是 2（不要改成 3）");
    }

    /** 侧键等未定义编号不得被猜成三键之一。 */
    @Test
    void unknownSdlButtonsPassThrough() {
        assertEquals(4, MouseButtons.fromSdl(4));
        assertEquals(0xFF, MouseButtons.fromSdl(0xFF));
    }
}
