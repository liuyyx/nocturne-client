package dev.nocturne.client.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自销毁闸门的幂等/竞态测试（先 kill 再拆资源；kill 与 dispatch 并发不得抛异常）。 */
class FrameDispatcherKillTest {

    @BeforeEach
    void reset() {
        FrameDispatcher.resetForTests();
    }

    /** 闸门是单向的：不复位会污染同一 JVM 里后续的帧分发用例。 */
    @org.junit.jupiter.api.AfterEach
    void restoreGate() {
        FrameDispatcher.resetForTests();
    }

    @Test
    void killedDispatcherStopsDispatching() {
        AtomicInteger runs = new AtomicInteger();
        FrameDispatcher.add(new Runnable() {
            @Override
            public void run() {
                runs.incrementAndGet();
            }
        });
        FrameDispatcher.dispatch();
        assertEquals(1, runs.get(), "precondition: dispatcher is live");

        FrameDispatcher.kill();
        FrameDispatcher.dispatch();
        FrameDispatcher.dispatch();
        assertEquals(1, runs.get(), "no listener may run after kill");
        assertTrue(FrameDispatcher.isKilled());
    }

    /**
     * 监听器 A 内 kill 后，同一帧快照里的监听器 B 绝不能再跑：B 可能把刚恢复的输入状态写坏。
     * panic 的还原动作正是从监听器/模块 tick 里触发的。
     */
    @Test
    void killingFromAListenerStopsLaterListenersInTheSameFrame() {
        AtomicInteger later = new AtomicInteger();
        FrameDispatcher.add(new Runnable() {
            @Override
            public void run() {
                FrameDispatcher.kill();
            }
        });
        FrameDispatcher.add(new Runnable() {
            @Override
            public void run() {
                later.incrementAndGet();
            }
        });

        FrameDispatcher.dispatch();

        assertEquals(0, later.get(), "kill 后同一快照中的后续监听器必须停止执行");
    }

    @Test
    void repeatedKillAndClearAreIdempotent() {
        FrameDispatcher.kill();
        FrameDispatcher.kill();
        FrameDispatcher.clear();
        FrameDispatcher.clear();
        assertTrue(FrameDispatcher.isKilled(), "clearing state must not revive the dispatcher");
        FrameDispatcher.dispatch();
    }

    @Test
    void concurrentKillAndDispatchDoNotThrow() throws Exception {
        final AtomicInteger runs = new AtomicInteger();
        FrameDispatcher.add(new Runnable() {
            @Override
            public void run() {
                runs.incrementAndGet();
            }
        });
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(3);
        Runnable dispatcher = new Runnable() {
            @Override
            public void run() {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        FrameDispatcher.dispatch();
                    }
                } catch (Throwable t) {
                    throw new AssertionError("dispatch must never throw", t);
                } finally {
                    done.countDown();
                }
            }
        };
        new Thread(dispatcher).start();
        new Thread(dispatcher).start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    start.await();
                    FrameDispatcher.kill();
                } catch (Throwable t) {
                    throw new AssertionError("kill must never throw", t);
                } finally {
                    done.countDown();
                }
            }
        }).start();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "threads must finish");
        assertTrue(FrameDispatcher.isKilled());
        int afterKill = runs.get();
        FrameDispatcher.dispatch();
        assertEquals(afterKill, runs.get(), "no runs after kill");
    }
}
