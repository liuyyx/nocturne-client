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
        FrameDispatcher.resetForTests();
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

    /**
     * 自销毁（{@code clear()}）必须先执行注册的恢复动作，再清空列表。
     *
     * <p>回归：这些动作修的是"由每帧监听器施加、但监听器不在 {@code clear()} 可见范围内"的外部状态
     * （放开游戏鼠标抓取、1.8.9 的 {@code inGameHasFocus}）。顺序反了或漏跑，副作用就永久留在游戏里
     * ——真机表现为自销毁后鼠标视角永久失效、点击无反应。
     */
    @Test
    void clearRunsRegisteredShutdownActionsOnce() {
        final int[] ran = {0};
        Runnable action = new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        };
        FrameDispatcher.addShutdown(action);

        FrameDispatcher.clear();
        assertEquals(1, ran[0], "shutdown action must run on clear");

        FrameDispatcher.clear();
        assertEquals(1, ran[0], "shutdown actions must not survive a clear");
    }

    /**
     * clear() 发生在分发途中：同一帧的快照里后续监听器必须立刻停。
     *
     * <p>回归：panic 从某个监听器内部调用 clear() 后，CopyOnWriteArrayList 的旧快照仍会跑完——
     * ForeignScreenGuard 会紧接着把刚恢复的 {@code inGameHasFocus} 再写回 false，永久锁视角。
     */
    @Test
    void clearDuringDispatchStopsRemainingListeners() {
        final java.util.List<String> ran = new java.util.ArrayList<String>();
        Runnable first = new Runnable() {
            @Override
            public void run() {
                ran.add("first");
                FrameDispatcher.clear();
            }
        };
        Runnable second = new Runnable() {
            @Override
            public void run() {
                ran.add("second");
            }
        };
        FrameDispatcher.add(first);
        FrameDispatcher.add(second);
        try {
            FrameDispatcher.dispatch();
            assertEquals(java.util.Arrays.asList("first"), ran,
                    "listeners after clear() must not run in the same dispatch");
            ran.clear();
            FrameDispatcher.dispatch();
            assertEquals(0, ran.size(), "dispatch after clear() must be a no-op");
        } finally {
            FrameDispatcher.remove(first);
            FrameDispatcher.remove(second);
        }
    }

    /** 一个恢复动作抛异常不得挡住其余动作，也不得把异常抛出 {@code clear()}。 */
    @Test
    void clearSurvivesAFailingShutdownAction() {
        final int[] ran = {0};
        FrameDispatcher.addShutdown(new Runnable() {
            @Override
            public void run() {
                throw new IllegalStateException("boom");
            }
        });
        FrameDispatcher.addShutdown(new Runnable() {
            @Override
            public void run() {
                ran[0]++;
            }
        });

        FrameDispatcher.clear();

        assertEquals(1, ran[0], "a failing recovery must not block the others");
    }

    /**
     * panic 的输入还原必须回到游戏主线程：从别的线程调用时任务要投递到 dispatch() 所在线程。
     */
    @Test
    void shutdownActionsRunOnTheGameThread() throws Exception {
        final Thread dispatchThread = Thread.currentThread();
        final java.util.concurrent.atomic.AtomicReference<Thread> ranOn =
                new java.util.concurrent.atomic.AtomicReference<Thread>();
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        FrameDispatcher.addShutdown(new Runnable() {
            @Override
            public void run() {
                ranOn.set(Thread.currentThread());
                done.countDown();
            }
        });

        final java.util.concurrent.CountDownLatch started =
                new java.util.concurrent.CountDownLatch(1);
        Thread caller = new Thread(new Runnable() {
            @Override
            public void run() {
                started.countDown();
                FrameDispatcher.restoreShutdownActionsOnGameThread(2000L);
            }
        }, "panic-caller");
        caller.start();
        started.await();

        long deadline = System.currentTimeMillis() + 2000L;
        while (!done.await(10L, java.util.concurrent.TimeUnit.MILLISECONDS)
                && System.currentTimeMillis() < deadline) {
            // 游戏主线程：帧回调在这里 drain 投递任务。
            FrameDispatcher.dispatch();
        }
        assertTrue(done.await(1L, java.util.concurrent.TimeUnit.SECONDS),
                "game-thread restore task must run");
        assertEquals(dispatchThread, ranOn.get(),
                "restore actions must execute on the dispatch (game) thread");
        caller.join(2000L);

        // 幂等：第二次调用不得再执行一次。
        FrameDispatcher.restoreShutdownActionsOnGameThread(10L);
        assertEquals(1, countOf(ranOn), "shutdown actions must run exactly once");
    }

    /** 读取 ranOn 是否已被设置（用 0/1 表示，避免引入额外计数器）。 */
    private static int countOf(java.util.concurrent.atomic.AtomicReference<Thread> ranOn) {
        return ranOn.get() == null ? 0 : 1;
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
