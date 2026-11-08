package dev.nocturne.core.attach;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NativePipeServer} 的句柄生命周期契约。
 *
 * <p>回归：旧实现用普通 {@code long} 保存句柄，并发 close 会各自读到同一个非 0 值并重复
 * CloseHandle；InputStream 在创建时缓存句柄，close 之后仍把已释放的句柄传进 native。
 * 两者在真机上分别表现为“偶尔 double free”和“退出时 use-after-close 崩溃”。
 */
class NativePipeServerTest {

    /** 统计调用的假后端：不加载 native。 */
    private static final class CountingBackend implements NativePipeServer.Backend {
        final AtomicInteger awaits = new AtomicInteger();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        volatile long lastClosed;

        @Override
        public int awaitConnection(long handle, long timeoutMs) {
            awaits.incrementAndGet();
            return 0;
        }

        @Override
        public int readPipe(long handle, byte[] buffer, int offset, int length) {
            reads.incrementAndGet();
            return 0;
        }

        @Override
        public void closePipe(long handle) {
            closes.incrementAndGet();
            lastClosed = handle;
        }
    }

    @Test
    void concurrentCloseReleasesTheHandleExactlyOnce() throws Exception {
        final CountingBackend backend = new CountingBackend();
        final NativePipeServer server = new NativePipeServer(0xCAFEL, backend);
        final int threads = 16;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        server.close();
                    } catch (Throwable ignored) {
                        // close 幂等：任何线程都不该抛异常
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, backend.closes.get(), "CloseHandle must run exactly once");
        assertEquals(0xCAFEL, backend.lastClosed);
    }

    @Test
    void readAfterCloseNeverEntersNative() throws Exception {
        CountingBackend backend = new CountingBackend();
        NativePipeServer server = new NativePipeServer(0x10L, backend);
        InputStream input = server.input();

        server.close();
        assertThrows(IOException.class, () -> input.read(new byte[4], 0, 4));
        // 单字节路径同样不得触底
        assertThrows(IOException.class, () -> input.read());
        assertEquals(0, backend.reads.get(), "no native read may happen after close");
    }

    @Test
    void awaitAfterCloseFailsWithoutNativeCall() throws Exception {
        CountingBackend backend = new CountingBackend();
        NativePipeServer server = new NativePipeServer(0x20L, backend);
        server.close();

        assertThrows(IOException.class, () -> server.awaitConnection(10L));
        assertEquals(0, backend.awaits.get());
    }
}
