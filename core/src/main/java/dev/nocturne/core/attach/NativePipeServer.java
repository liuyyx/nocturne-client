package dev.nocturne.core.attach;

import java.io.IOException;
import java.io.InputStream;

/**
 * 原生命名管道服务端包装（Java 侧句柄管理，IO 经 JNI 直调）。
 *
 * <p>实例由 {@link WindowsAttachNative#createPipeServer} 创建，持有原生管道句柄；
 * {@link #close()} 负责 Disconnect + CloseHandle，可重复调用（幂等）。
 */
final class NativePipeServer implements WindowsAttachStrategy.PipeServer {

    /** 原生管道句柄；关闭后置 0。 */
    private long handle;

    NativePipeServer(long handle) {
        if (handle == 0L) {
            throw new IllegalArgumentException("null pipe handle");
        }
        this.handle = handle;
    }

    @Override
    public void awaitConnection(long timeoutMs) throws IOException {
        long current = handle;
        if (current == 0L) {
            throw new IOException("pipe already closed");
        }
        int code = NativePipeIo.awaitConnection(current, timeoutMs);
        if (code != 0) {
            throw new IOException("attach pipe accept failed: " + code);
        }
    }

    @Override
    public InputStream input() {
        final long current = handle;
        if (current == 0L) {
            throw new IllegalStateException("pipe already closed");
        }
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int got = read(one, 0, 1);
                return got <= 0 ? -1 : one[0] & 0xFF;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int got = NativePipeIo.readPipe(current, buffer, offset, length);
                if (got < 0) {
                    throw new IOException("attach pipe read failed: " + got);
                }
                return got == 0 ? -1 : got;
            }
        };
    }

    @Override
    public void close() throws IOException {
        long current = handle;
        handle = 0L;
        if (current != 0L) {
            NativePipeIo.closePipe(current);
        }
    }
}
