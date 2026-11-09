package gg.vape.sync;

import java.util.concurrent.atomic.AtomicInteger;

/** 测试替身：stop() 被 panic/teardown 反射调用。 */
public final class SyncThread {
    public static final SyncThread INSTANCE = new SyncThread();
    public static final AtomicInteger STOPS = new AtomicInteger();

    private SyncThread() {
    }

    public void stop() {
        STOPS.incrementAndGet();
    }
}
