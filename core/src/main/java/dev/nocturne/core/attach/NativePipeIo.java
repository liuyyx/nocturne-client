package dev.nocturne.core.attach;

/**
 * 原生命名管道 IO 声明（随 {@code nocturne-attach.dll} 一起交付）。
 *
 * <p>返回值约定：0=成功，负数=读到 EOF/对端关闭（仅读操作），正数=Win32 错误码。
 */
final class NativePipeIo {

    /** 工具类，禁止实例化。 */
    private NativePipeIo() {
    }

    /**
     * 等待目标连入本次命令的服务端管道。
     *
     * @param pipeHandle {@code CreateNamedPipe} 返回的句柄
     * @param timeoutMs  等待上限（毫秒）
     * @return 0=已连入；正数=Win32 错误码（含超时）
     */
    static int awaitConnection(long pipeHandle, long timeoutMs) {
        WindowsAttachNative.ensureLoaded();
        return nAwaitConnection(pipeHandle, timeoutMs);
    }

    /**
     * 从已连入的管道读数据。
     *
     * @param pipeHandle 管道句柄
     * @param buffer     目标数组
     * @param offset     起始偏移
     * @param length     最多读字节数
     * @return 读到的字节数；0=对端关闭（EOF）；负数=Win32 错误码取反
     */
    static int readPipe(long pipeHandle, byte[] buffer, int offset, int length) {
        WindowsAttachNative.ensureLoaded();
        return nReadPipe(pipeHandle, buffer, offset, length);
    }

    /** 关闭管道句柄（Disconnect + CloseHandle，幂等视角：句柄 0 直接返回）。 */
    static void closePipe(long pipeHandle) {
        if (pipeHandle == 0L) {
            return;
        }
        WindowsAttachNative.ensureLoaded();
        nClosePipe(pipeHandle);
    }

    private static native int nAwaitConnection(long pipeHandle, long timeoutMs);

    private static native int nReadPipe(long pipeHandle, byte[] buffer, int offset, int length);

    private static native void nClosePipe(long pipeHandle);
}
