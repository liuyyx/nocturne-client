package dev.nocturne.core.attach;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 原生命名管道服务端包装（Java 侧句柄管理，IO 经 JNI 直调）。
 *
 * <p>实例由 {@link WindowsAttachNative#createPipeServer} 创建，持有原生管道句柄；
 * {@link #close()} 负责 Disconnect + CloseHandle，可重复/并发调用。
 *
 * <p><b>句柄生命周期</b>：句柄用 {@link AtomicLong} 保存。{@code close()} 用
 * {@code getAndSet(0)} 原子地取走唯一一份句柄，因此并发 close 里只有一个线程会真正调用
 * CloseHandle；{@code InputStream} 每次读都取当前值，关闭后读到 0 直接抛 {@link IOException}，
 * 不会把已释放的句柄再传进 native（use-after-close）。
 *
 * <p>IO 经 {@link Backend} 抽象，默认实现直调 {@link NativePipeIo}；测试用假实现统计
 * CloseHandle/read 的调用次数，不需要加载 native 库。
 */
final class NativePipeServer implements WindowsAttachStrategy.PipeServer {

    /** 原生命名管道 IO 的最小面；生产实现转调 {@link NativePipeIo}。 */
    interface Backend {
        int awaitConnection(long handle, long timeoutMs);

        int readPipe(long handle, byte[] buffer, int offset, int length);

        void closePipe(long handle);
    }

    /** 生产实现：直调 JNI。 */
    private static final Backend NATIVE = new Backend() {
        @Override
        public int awaitConnection(long handle, long timeoutMs) {
            return NativePipeIo.awaitConnection(handle, timeoutMs);
        }

        @Override
        public int readPipe(long handle, byte[] buffer, int offset, int length) {
            return NativePipeIo.readPipe(handle, buffer, offset, length);
        }

        @Override
        public void closePipe(long handle) {
            NativePipeIo.closePipe(handle);
        }
    };

    /** 原生管道句柄；关闭后置 0。 */
    private final AtomicLong handle;

    /** IO 后端（默认真实 JNI）。 */
    private final Backend backend;

    NativePipeServer(long handle) {
        this(handle, NATIVE);
    }

    /**
     * 测试用构造：换成可统计的 IO 后端。
     *
     * @param handle  非 0 句柄
     * @param backend IO 后端；{@code null} 视为生产实现
     */
    NativePipeServer(long handle, Backend backend) {
        if (handle == 0L) {
            throw new IllegalArgumentException("null pipe handle");
        }
        this.handle = new AtomicLong(handle);
        this.backend = backend == null ? NATIVE : backend;
    }

    @Override
    public void awaitConnection(long timeoutMs) throws IOException {
        long current = handle.get();
        if (current == 0L) {
            throw new IOException("pipe already closed");
        }
        int code = backend.awaitConnection(current, timeoutMs);
        if (code != 0) {
            throw new IOException("attach pipe accept failed: " + code);
        }
    }

    @Override
    public InputStream input() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int got = read(one, 0, 1);
                return got <= 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                // 每次读都取当前句柄：close() 之后为 0，绝不能把已释放的句柄再传进 native。
                long current = handle.get();
                if (current == 0L) {
                    throw new IOException("pipe already closed");
                }
                int got = backend.readPipe(current, buffer, offset, length);
                if (got < 0) {
                    throw new IOException("attach pipe read failed: " + got);
                }
                return got == 0 ? -1 : got;
            }
        };
    }

    @Override
    public void close() throws IOException {
        // getAndSet(0)：并发 close 只有一个线程拿到非 0 值，CloseHandle 恰好一次。
        long current = handle.getAndSet(0L);
        if (current != 0L) {
            backend.closePipe(current);
        }
    }
}
