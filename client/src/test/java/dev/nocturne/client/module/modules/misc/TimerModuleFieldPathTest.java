package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Timer 可用性判定：没有 ASM 钩子但字段可写时（1.8.9）必须照常工作。
 *
 * <p>旧实现只看 {@code GameplayHook.isWired("timer")}：1.8.9/1.12.2/1.16.5/1.20.1 都没有
 * timer delta 钩子，于是模块在能写 Timer 字段的版本上被错误地整体停用。
 */
class TimerModuleFieldPathTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
    }

    /** 1.8.9 形状：无钩子、Timer.timerSpeed 可写。 */
    @Test
    void worksThroughTimerSpeedFieldWithoutHook() {
        TimerSpeed timer = new TimerSpeed();
        FakeMinecraft.instance.timer = timer;
        NocturneClient client = NocturneClient.boot(null);
        client.setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));

        TimerModule module = new TimerModule();
        module.setEnabled(true);
        try {
            module.onTick();
            assertEquals(1.8f, timer.timerSpeed, 1e-6,
                    "默认 Always 1.8x 必须真正写进 1.8.9 的 Timer.timerSpeed");
        } finally {
            module.setEnabled(false);
        }
    }

    /** bridge 晚于模块就绪时，可用性判定必须延后，不能永久缓存成不可用。 */
    @Test
    void retriesCapabilityCheckWhenBridgeArrivesLate() throws Exception {
        TimerSpeed timer = new TimerSpeed();
        FakeMinecraft.instance.timer = timer;
        NocturneClient client = NocturneClient.boot(null);

        TimerModule module = new TimerModule();
        module.setEnabled(true);
        try {
            module.onTick();
            assertEquals(1.0f, timer.timerSpeed, 1e-6, "no bridge: nothing may be written");
            assertEquals(null, available(module), "bridge missing must not cache a verdict");

            client.setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));
            module.onTick();
            assertEquals(1.8f, timer.timerSpeed, 1e-6, "after the bridge arrives the field path must work");
        } finally {
            module.setEnabled(false);
        }
    }

    /** 没有可写字段的版本：判定为不可用，且不抛异常。 */
    @Test
    void marksUnavailableWhenNoFieldIsWritable() throws Exception {
        FakeMinecraft.instance.timer = new Object();
        NocturneClient client = NocturneClient.boot(null);
        client.setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));

        TimerModule module = new TimerModule();
        module.setEnabled(true);
        try {
            module.onTick();
            assertNotEquals(null, available(module));
            assertEquals(Boolean.FALSE, available(module));
        } finally {
            module.setEnabled(false);
        }
    }

    private static Boolean available(TimerModule module) throws Exception {
        Field field = TimerModule.class.getDeclaredField("available");
        field.setAccessible(true);
        return (Boolean) field.get(module);
    }

    /** 1.8.9 Timer 的倍率字段形状。 */
    static final class TimerSpeed {
        @SuppressWarnings("unused")
        float timerSpeed = 1.0f;
    }
}
