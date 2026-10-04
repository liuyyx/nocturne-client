package dev.noturne.client.runtime;

import dev.noturne.client.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NoturneRuntime} 的首帧诊断与监听器注册测试。
 *
 * <p>M-82：{@code firstFrameLogged} 曾在检查 TRACE 之前置位，使安装早于 {@code trace(true)} 时
 * 「frame hook is live」永不打印——而它恰是判断钩子是否生效的唯一诊断信号。
 */
class NoturneRuntimeTest {

    private boolean savedFirstFrame;

    @BeforeEach
    void setUp() throws Exception {
        savedFirstFrame = readFirstFrame();
        writeFirstFrame(false);
        NoturneRuntime.trace(false);
        TestSupport.resetClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        NoturneRuntime.trace(false);
        writeFirstFrame(savedFirstFrame);
        TestSupport.resetClient();
    }

    /** M-82：TRACE 关闭时的帧不得消耗「首帧」这一次机会 */
    @Test
    void traceFalseDoesNotConsumeTheFirstFrameChance() throws Exception {
        NoturneRuntime.onFrame();
        assertFalse(readFirstFrame(), "TRACE=false must not consume the one-shot first-frame log");

        NoturneRuntime.trace(true);
        NoturneRuntime.onFrame();
        assertTrue(readFirstFrame(), "the first frame after trace(true) must be logged");
    }

    /** M-83：重复注册同一监听器只算一次，避免每帧被驱动多次 */
    @Test
    void listenersAreRegisteredIdempotently() {
        FrameListener listener = new FrameListener() {
            @Override
            public void onFrame() {
            }
        };
        int before = NoturneRuntime.listenerCount();
        NoturneRuntime.addListener(listener);
        NoturneRuntime.addListener(listener);
        assertEquals(before + 1, NoturneRuntime.listenerCount(), "duplicate listener must be ignored");
        NoturneRuntime.removeListener(listener);
        assertEquals(before, NoturneRuntime.listenerCount());
    }

    private static boolean readFirstFrame() throws Exception {
        Field field = NoturneRuntime.class.getDeclaredField("firstFrameLogged");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    private static void writeFirstFrame(boolean value) throws Exception {
        Field field = NoturneRuntime.class.getDeclaredField("firstFrameLogged");
        field.setAccessible(true);
        field.setBoolean(null, value);
    }
}
