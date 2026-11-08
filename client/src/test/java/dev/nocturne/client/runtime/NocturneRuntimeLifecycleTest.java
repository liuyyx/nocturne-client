package dev.nocturne.client.runtime;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.RenderEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生命周期回归：监听器包装缓存在 shutdown 后清空、框架同步线程被显式打断、
 * RenderEvent 复用同一实例。
 */
class NocturneRuntimeLifecycleTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
        NocturneRuntime.shutdown();
    }

    @AfterEach
    void tearDown() {
        NocturneRuntime.shutdown();
        TestSupport.resetClient();
    }

    /** 同一监听器重复 attach 不应让包装缓存增长；shutdown 后清空，再 attach 从零开始。 */
    @Test
    void wrappersDoNotGrowAcrossAttachAndAreClearedOnShutdown() {
        FrameListener listener = new FrameListener() {
            @Override
            public void onFrame() {
            }
        };
        NocturneRuntime.addListener(listener);
        NocturneRuntime.addListener(listener);
        assertEquals(1, NocturneRuntime.wrapperCountForTests(), "同一监听器只应缓存一个包装");
        assertEquals(1, FrameDispatcher.count());

        NocturneRuntime.shutdown();
        assertEquals(0, NocturneRuntime.wrapperCountForTests(), "shutdown 必须清空包装缓存");
        assertEquals(0, FrameDispatcher.count());

        NocturneRuntime.addListener(listener);
        assertEquals(1, NocturneRuntime.wrapperCountForTests(), "shutdown 后重新 attach 不应残留旧条目");
    }

    /** vendor 侧同步工作线程不归分发器管，shutdown 必须显式打断，否则会拖住 JVM 退出。 */
    @Test
    void shutdownInterruptsVendorSyncWorkers() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                }
            }
        }, "Vape settings sync worker");
        worker.setDaemon(true);
        worker.start();

        NocturneRuntime.shutdown();
        worker.join(2000L);
        assertFalse(worker.isAlive(), "shutdown 后同步线程必须退出");
        assertTrue(interrupted.get(), "shutdown 应通过 interrupt 唤醒同步线程");
    }

    /** RenderEvent 每帧复用同一实例，序号单调递增；无订阅者时不改写载荷。 */
    @Test
    void renderEventIsReusedAcrossFrames() {
        NocturneClient client = NocturneClient.boot(null);
        List<RenderEvent> seen = new ArrayList<RenderEvent>();
        // 载荷是复用实例：序号必须在投递回调内取，事后从列表读只能拿到最后一次改写后的值。
        List<Long> sequences = new ArrayList<Long>();
        List<Long> nanos = new ArrayList<Long>();
        EventBus.Subscription subscription = client.events().subscribe(RenderEvent.class, event -> {
            seen.add(event);
            sequences.add(event.sequence);
            nanos.add(event.frameNanos);
        });
        try {
            NocturneRuntime.postRenderEvent();
            NocturneRuntime.postRenderEvent();
        } finally {
            client.events().unsubscribe(subscription);
        }
        assertEquals(2, seen.size());
        assertSame(seen.get(0), seen.get(1), "每帧不得新建 RenderEvent");
        assertTrue(sequences.get(1) > sequences.get(0), "序号必须推进");
        assertTrue(nanos.get(1) >= nanos.get(0), "时间戳必须为当帧");
    }

    /** 没有任何订阅者时投递是无副作用的空操作。 */
    @Test
    void renderEventWithoutSubscribersIsANoOp() {
        NocturneClient.boot(null);
        NocturneRuntime.postRenderEvent();
        NocturneRuntime.postRenderEvent();
    }
}
