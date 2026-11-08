package dev.nocturne.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentLog} 的两条分支：有游戏 Log4j2 时只进游戏日志（1.8.9 的 latest.log），
 * 没有时退回 stdout。1.8.9 上 stdout 不进 latest.log，注入结果因此完全看不见。
 */
class AgentLogTest {

    /** 假 Log4j2 日志器：记录 info 收到的行。 */
    static final class FakeGameLogger {
        final List<String> lines = new ArrayList<String>();

        public void info(String line) {
            lines.add(line);
        }
    }

    private PrintStream originalOut;
    private ByteArrayOutputStream captured;

    private void captureStdout() {
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true));
    }

    @AfterEach
    void restore() {
        if (originalOut != null) {
            System.setOut(originalOut);
        }
        AgentLog.resetForTests();
    }

    @Test
    void prefersTheGameLogWhenLog4jIsAvailable() throws Exception {
        FakeGameLogger logger = new FakeGameLogger();
        Method info = FakeGameLogger.class.getMethod("info", String.class);
        AgentLog.installGameLoggerForTests(info, logger);

        captureStdout();
        AgentLog.info("agent loaded via agentmain");

        assertEquals(1, logger.lines.size(), "the line must go to the game log exactly once");
        assertEquals("[nocturne] agent loaded via agentmain", logger.lines.get(0));
        assertTrue(captured.toString(StandardCharsets.UTF_8.name()).isEmpty(),
                "stdout must stay untouched when Log4j2 accepted the line");
    }

    @Test
    void fallsBackToStdoutWithoutLog4j() {
        captureStdout();
        AgentLog.info("bridge failed");

        assertTrue(captured.toString().contains("[nocturne] bridge failed"),
                "without Log4j2 the line must still reach stdout");
    }

    @Test
    void fallsBackToStdoutWhenTheGameLoggerThrows() throws Exception {
        Method info = BrokenLogger.class.getMethod("info", String.class);
        AgentLog.installGameLoggerForTests(info, new BrokenLogger());

        captureStdout();
        AgentLog.info("overlay attached");

        assertTrue(captured.toString().contains("[nocturne] overlay attached"),
                "a broken Log4j2 must never swallow the log line");
    }

    /** 调用即抛的日志器：模拟目标侧 Log4j2 被替换/卸载。 */
    public static final class BrokenLogger {
        public void info(String line) {
            throw new IllegalStateException("logger gone");
        }
    }
}
