package dev.nocturne.client.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScrollState} 的契约：累加、一次性取走、脏值过滤。
 *
 * <p>为什么单钉它：SDL 的滚轮没有轮询接口，增量必须"攒起来等界面来取"。取走语义写错（比如不清零，
 * 或每帧重复返回同一份）表现为界面疯狂滚动；不清零还会让关闭界面期间累积的增量在打开瞬间一次爆出来。
 */
class ScrollStateTest {

    @BeforeEach
    void reset() {
        ScrollState.reset();
    }

    /** 空状态取走必须是 0：界面每帧都会取一次，返回别的值等于每帧都在滚。 */
    @Test
    void emptyStateTakesZero() {
        assertEquals(0d, ScrollState.take(), 0.0001);
    }

    /** 累积多次后一次取走：一次滚动可能拆成多个事件。 */
    @Test
    void accumulatesUntilTaken() {
        ScrollState.accumulate(1d);
        ScrollState.accumulate(2d);
        ScrollState.accumulate(-0.5d);

        assertEquals(2.5d, ScrollState.take(), 0.0001);
        assertEquals(0d, ScrollState.take(), 0.0001, "取走后必须清零");
    }

    /** 方向与大小原样保留：界面靠符号决定滚哪边。 */
    @Test
    void keepsDirection() {
        ScrollState.accumulate(-1.5d);
        assertEquals(-1.5d, ScrollState.take(), 0.0001);
    }

    /** 0/NaN/无穷必须被忽略：NaN 一旦进累加器就永远清不掉（0 也取不出来）。 */
    @Test
    void ignoresUnusableValues() {
        ScrollState.accumulate(0d);
        ScrollState.accumulate(Double.NaN);
        ScrollState.accumulate(Double.POSITIVE_INFINITY);
        ScrollState.accumulate(1d);

        assertEquals(1d, ScrollState.take(), 0.0001);
    }

    /**
     * 织入标记必须转发到只存在于 bootstrap 的 {@code KeySink}。
     *
     * <p>叠加层据此决定要不要顶掉 GLFW 回调：{@code glfwSetScrollCallback} 是单槽位接口，顶掉之后
     * 游戏自己的 {@code MouseHandler#onScroll} 就再也不会被调用——这个标记读不到，滚轮会彻底失效。
     */
    @Test
    void exposesTheGameHookFlag() {
        assertFalse(ScrollState.gameHookPresent(), "fresh state must report no game hook");
        ScrollState.markGameHook();
        assertTrue(ScrollState.gameHookPresent(), "flag must survive the class-identity split");
    }
}
