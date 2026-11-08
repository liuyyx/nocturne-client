package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TimeChangerModule} 的能力探测测试。
 *
 * <p>26.3 没有 {@code ClientLevel#dayTime}/{@code LevelData#setDayTime}，
 * {@link GameBridge#setDayTime(long)} 返回 {@code false}。模块必须在探测到一次失败后标记
 * 不可用、只提示一次，而不是每 tick 重试（真机上表现为每 tick 一次空转反射调用）。
 */
class TimeChangerModuleTest {

    private ClassLoader savedTccl;
    private PrintStream savedOut;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() throws Exception {
        savedTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        TestSupport.resetClient();
        FakeMinecraft.reset();
        savedOut = System.out;
        captured = new ByteArrayOutputStream();
        // Java 8 目标：只能按编码名构造，不能传 Charset。
        System.setOut(new PrintStream(captured, true, "UTF-8"));
    }

    @AfterEach
    void tearDown() {
        System.setOut(savedOut);
        TestSupport.resetClient();
        Thread.currentThread().setContextClassLoader(savedTccl);
    }

    /** 有时间入口的版本：直接写入 dayTime 字段。 */
    @Test
    void writesTimeWhenTheVersionHasAnEntry() {
        FakeLevel level = new FakeLevel();
        FakeMinecraft.instance.level = level;
        NocturneClient.boot(null).setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));

        TimeChangerModule module = new TimeChangerModule();
        module.setEnabled(true);
        module.onTick();

        assertEquals(6000L, level.dayTime, "the configured time must be written to the level");
        assertTrue(!output().contains("该版本无可用时间入口"),
                "a working entry must not report the module as unavailable");
    }

    /** 没有时间入口的版本：只探测一次并提示一次，之后不再调用。 */
    @Test
    void reportsMissingEntryOnceAndStopsProbing() {
        MissingEntryLevel level = new MissingEntryLevel();
        FakeMinecraft.instance.level = level;
        NocturneClient.boot(null).setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));

        TimeChangerModule module = new TimeChangerModule();
        module.setEnabled(true);
        module.onTick();
        module.onTick();
        module.onTick();

        assertEquals(1, level.levelData.calls,
                "a missing time entry must be probed once, not retried every tick");
        assertEquals(1, occurrences(output(), "该版本无可用时间入口"),
                "the unavailable notice must be emitted exactly once");

        module.setEnabled(false);
        module.setEnabled(true);
        module.onTick();
        assertEquals(2, level.levelData.calls, "re-enabling must probe again");
    }

    /** 未进入世界时无法判定能力：既不写入也不误报不可用，等世界就绪再探测。 */
    @Test
    void waitsForWorldBeforeJudgingCapability() {
        FakeMinecraft.instance.level = null;
        NocturneClient.boot(null).setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));

        TimeChangerModule module = new TimeChangerModule();
        module.setEnabled(true);
        module.onTick();
        assertTrue(!output().contains("该版本无可用时间入口"),
                "no world is not evidence that the version lacks a time entry");

        FakeLevel level = new FakeLevel();
        FakeMinecraft.instance.level = level;
        module.onTick();
        assertEquals(6000L, level.dayTime, "the module must start working once a world loads");
    }

    private String output() {
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    /** 有时间入口的替身世界：{@code dayTime} 字段直接可写。 */
    public static final class FakeLevel {
        public long dayTime;
    }

    /** 没有时间入口的替身世界：没有 {@code dayTime} 字段，{@code levelData.setDayTime} 抛异常。 */
    public static final class MissingEntryLevel {
        public final NoEntryData levelData = new NoEntryData();
    }

    /** 计数并模拟 26.3 的“方法不存在”：调用即失败。 */
    public static final class NoEntryData {
        public int calls;

        public void setDayTime(long time) {
            calls++;
            throw new IllegalStateException("absent in 26.3");
        }
    }
}
