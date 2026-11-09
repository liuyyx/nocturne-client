package dev.nocturne.client.game;

import dev.nocturne.client.mapping.ClassType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计时倍率的三种真实字段形状。
 *
 * <p>1.8.9 写 {@code Timer.timerSpeed}（倍率本身）；1.12.2 写
 * {@code Timer.tickLength = 50/speed}；1.16.5+ 写 {@code Timer.msPerTick = 50/speed}
 * （1.21+ 是 {@code DeltaTracker$Timer}）。旧实现只读 {@code Minecraft.deltaTracker}
 * 的 {@code timerSpeed}，1.12.2 起全部静默失败（模块开关亮着但速度不变）。
 */
class GameBridgeTimerTest {

    @BeforeEach
    void setUp() {
        FakeMinecraft.reset();
    }

    @AfterEach
    void tearDown() {
        FakeMinecraft.reset();
    }

    /** 1.8.9 形状：字段叫 deltaTracker，写 timerSpeed 倍率。 */
    @Test
    void writesLegacyTimerSpeedField() {
        TimerSpeed timer = new TimerSpeed();
        FakeMinecraft.instance.deltaTracker = timer;
        GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));

        assertTrue(bridge.setTimerSpeed(1.5f));
        assertEquals(1.5f, timer.timerSpeed, 1e-6);
    }

    /** 1.12.2 形状：Timer.tickLength 是「一 tick 多少毫秒」，倍率要取倒数。 */
    @Test
    void writesTickLengthAsFiftyOverSpeed() {
        TickLength timer = new TickLength();
        FakeMinecraft.instance.timer = timer;
        GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));

        assertTrue(bridge.setTimerSpeed(2.0f));
        assertEquals(25.0f, timer.tickLength, 1e-6);
    }

    /** 1.16.5/1.20.1/1.21+ 形状：msPerTick = 50/speed。 */
    @Test
    void writesMsPerTickAsFiftyOverSpeed() {
        MsPerTick timer = new MsPerTick();
        FakeMinecraft.instance.timer = timer;
        GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));

        assertTrue(bridge.setTimerSpeed(1.25f));
        assertEquals(40.0f, timer.msPerTick, 1e-6);
    }

    /** 没有任何可写字段的版本（例如 26.3 若字段变为不可写）：显式失败，不假装生效。 */
    @Test
    void failsWhenNoTimerFieldIsWritable() {
        FakeMinecraft.instance.timer = new Object();
        GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));

        assertFalse(bridge.setTimerSpeed(2.0f));
    }

    /** 非法倍率直接拒绝，避免把 NaN/0 写进游戏。 */
    @Test
    void rejectsNonPositiveOrInfiniteSpeed() {
        TickLength timer = new TickLength();
        FakeMinecraft.instance.timer = timer;
        GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));

        assertFalse(bridge.setTimerSpeed(0.0f));
        assertFalse(bridge.setTimerSpeed(-1.0f));
        assertFalse(bridge.setTimerSpeed(Float.NaN));
        assertFalse(bridge.setTimerSpeed(Float.POSITIVE_INFINITY));
        assertEquals(50.0f, timer.tickLength, 1e-6, "rejected values must not touch the timer");
    }

    /** 1.8.9 的真实 Timer 类名（{@code avl}）对应的字段形状。 */
    static final class TimerSpeed {
        @SuppressWarnings("unused")
        float timerSpeed = 1.0f;
    }

    /** 1.12.2 的真实 Timer 字段形状。 */
    static final class TickLength {
        @SuppressWarnings("unused")
        float tickLength = 50.0f;
    }

    /** 1.16.5–1.21.x 的真实 Timer 字段形状。 */
    static final class MsPerTick {
        @SuppressWarnings("unused")
        float msPerTick = 50.0f;
    }
}
