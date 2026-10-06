package dev.nocturne.client.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FrameDispatcher} 的首帧诊断与监听器注册契约。
 *
 * <p>M-82：{@code firstFrameLogged} 曾在检查 TRACE 之前置位，使 trace(true) 晚于首帧时分发时
 * 「frame hook is live」永不打印——而它恰是判断帧钩子是否生效的唯一诊断信号。
 *
 * <p>被测对象是 {@link FrameDispatcher} 而不是 {@link NocturneRuntime}：后者是注入侧入口，只经反射
 * 转发到被 bootstrap 加载的分发器，单测环境里没有绑定，因而驱动不了真实状态。
 */
class FrameDispatcherTest {

    private boolean savedFirstFrame;

    @BeforeEach
    void setUp() throws Exception {
        savedFirstFrame = readFirstFrame();
        writeFirstFrame(false);
        FrameDispatcher.trace(false);
    }

    @AfterEach
    void tearDown() throws Exception {
        FrameDispatcher.trace(false);
        writeFirstFrame(savedFirstFrame);
    }

    /** M-82：TRACE 关闭时的帧不得消耗「首帧」这一次机会。 */
    @Test
    void traceFalseDoesNotConsumeTheFirstFrameChance() throws Exception {
        FrameDispatcher.dispatch();
        assertFalse(readFirstFrame(), "TRACE=false must not consume the one-shot first-frame log");

        FrameDispatcher.trace(true);
        FrameDispatcher.dispatch();
        assertTrue(readFirstFrame(), "the first frame after trace(true) must be logged");
    }

    /** M-83：重复注册同一动作只算一次，避免每帧被驱动多次。 */
    @Test
    void listenersAreRegisteredIdempotently() {
        Runnable action = new Runnable() {
            @Override
            public void run() {
            }
        };
        int before = FrameDispatcher.count();
        FrameDispatcher.add(action);
        FrameDispatcher.add(action);
        assertEquals(before + 1, FrameDispatcher.count(), "duplicate action must be ignored");
        FrameDispatcher.remove(action);
        assertEquals(before, FrameDispatcher.count());
    }

    private static boolean readFirstFrame() throws Exception {
        Field field = FrameDispatcher.class.getDeclaredField("firstFrameLogged");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    private static void writeFirstFrame(boolean value) throws Exception {
        Field field = FrameDispatcher.class.getDeclaredField("firstFrameLogged");
        field.setAccessible(true);
        field.setBoolean(null, value);
    }
}
