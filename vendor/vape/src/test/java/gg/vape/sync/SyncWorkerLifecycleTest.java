package gg.vape.sync;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线程生命周期回归：两个同步工作线程必须能被显式停止，且由 {@link SyncThread} 创建时是 daemon。
 *
 * <p>旧实现用 {@code while (!Vape.INSTANCE.isEnabled())}：该字段全源码无赋值、恒为 false，
 * 线程永不退出且非 daemon——JVM 关闭时被拖住。
 */
class SyncWorkerLifecycleTest {

    /** 运行标志清零后工作线程必须自行退出。 */
    @Test
    void storeWorkerExitsWhenRunningFlagClears() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread worker = new Thread(new SyncStoreRequestWorker(running::get), "test-store-worker");
        worker.setDaemon(true);
        worker.start();
        running.set(false);
        worker.join(2000L);
        assertFalse(worker.isAlive(), "运行标志清零后线程必须退出");
    }

    /** interrupt 必须唤醒 sleep 中的工作线程（不能吞掉 InterruptedException 继续自旋）。 */
    @Test
    void interruptStopsBothWorkers() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread store = new Thread(new SyncStoreRequestWorker(running::get), "test-store-worker");
        Thread debounce = new Thread(new SyncDebounceWorker(running::get), "test-debounce-worker");
        store.setDaemon(true);
        debounce.setDaemon(true);
        store.start();
        debounce.start();

        store.interrupt();
        debounce.interrupt();
        store.join(2000L);
        debounce.join(2000L);
        assertFalse(store.isAlive(), "保存线程必须响应 interrupt");
        assertFalse(debounce.isAlive(), "防抖线程必须响应 interrupt");
    }

    /** SyncThread 启动的线程是 daemon，stop() 后全部退出。 */
    @Test
    void syncThreadWorkersAreDaemonAndStopClearsThem() throws Exception {
        SyncThread sync = new SyncThread(null);
        sync.start();
        Thread debounce = sync.debounceThread;
        assertTrue(debounce != null && debounce.isDaemon(), "同步线程必须是 daemon");
        assertTrue(sync.isRunning());

        sync.stop();
        debounce.join(2000L);
        assertFalse(debounce.isAlive(), "stop() 后防抖线程必须退出");
        assertFalse(sync.isRunning());
    }
}
