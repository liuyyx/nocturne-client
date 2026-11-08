package dev.nocturne.client.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：钩子失败日志按**钩子名**限流，不再被单个高频钩子独占全局五秒窗口。
 *
 * <p>旧实现只有一个全局时间戳：{@code mouse} 先失败一次后，同一窗口内 {@code key}/{@code packetSend}
 * 的首错全部被吞掉，排查时看不到真正坏掉的钩子。
 */
class GameplayHookFailureLogTest {

    private PrintStream original;

    @BeforeEach
    void setUp() {
        original = System.out;
        GameplayHook.resetFailureLogsForTests();
    }

    @AfterEach
    void tearDown() {
        System.setOut(original);
        GameplayHook.resetFailureLogsForTests();
    }

    private String capture(Runnable action) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true));
        try {
            action.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString();
    }

    /** 同一秒内两个不同钩子失败：两行都要出现。 */
    @Test
    void differentHooksInTheSameWindowBothLog() {
        String out = capture(() -> {
            GameplayHook.failure("mouse", new IllegalStateException("boom-mouse"));
            GameplayHook.failure("key", new IllegalStateException("boom-key"));
        });
        assertTrue(out.contains("gameplay hook mouse failed"), out);
        assertTrue(out.contains("gameplay hook key failed"), out);
    }

    /** 同一钩子重复失败仍限流为一行。 */
    @Test
    void sameHookIsRateLimited() {
        String out = capture(() -> {
            GameplayHook.failure("mouse", new IllegalStateException("boom-1"));
            GameplayHook.failure("mouse", new IllegalStateException("boom-2"));
        });
        assertEquals(1, occurrences(out, "gameplay hook mouse failed"), out);
    }

    /** 全局总量上限仍在：大量不同钩子同时失败也不会刷屏。 */
    @Test
    void globalWindowHasAnUpperBound() {
        String out = capture(() -> {
            for (int i = 0; i < 20; i++) {
                GameplayHook.failure("hook-" + i, new IllegalStateException("boom-" + i));
            }
        });
        assertEquals(8, occurrences(out, "gameplay hook hook-"), out);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
