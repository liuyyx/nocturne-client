package dev.nocturne.core.attach;

import java.io.IOException;

/**
 * Linux / macOS attach 原生通道声明（自研实现，仅行为对齐 HotSpot）。
 *
 * <p>生产实现是随 core 一起编译的 JNI 小库
 * （{@code core/src/main/native/unix/attach_unix.c}，Gradle 在 Linux/macOS 上用 {@code cc}
 * 现地编译为 {@code nocturne-attach.so} / {@code .dylib}，其他平台跳过）。库在首次调用时
 * 从 jar 资源解压到临时目录加载；加载失败时入口抛 {@link UnsatisfiedLinkError}，
 * 策略层按“本策略不可用”处理——不伪装成功，也不静默跳过。
 *
 * <p>与 Windows 侧的差别：Windows 是「服务端命名管道 + 向目标投递操作」（目标回连），
 * 而 Unix 是「直接连上目标自己的 attach 监听套接字」，因此这里没有服务端概念，
 * 只有一条连接上的写与读。
 */
public final class PosixAttachNative {

    /** 工具类，禁止实例化。 */
    private PosixAttachNative() {
    }

    /** 原生库是否已尝试加载（只试一次，失败即永久放弃）。 */
    private static volatile boolean loadAttempted;

    /** 原生库是否可用。 */
    private static volatile boolean available;

    /**
     * 确保原生库已加载（首次调用时从 jar 资源解压 + {@code System.load}）。
     *
     * @throws UnsatisfiedLinkError 库缺失或加载失败时抛出
     */
    public static void ensureLoaded() {
        if (loadAttempted) {
            if (!available) {
                throw new UnsatisfiedLinkError("unix attach native library not available");
            }
            return;
        }
        synchronized (PosixAttachNative.class) {
            if (loadAttempted) {
                if (!available) {
                    throw new UnsatisfiedLinkError("unix attach native library not available");
                }
                return;
            }
            loadAttempted = true;
            available = NativeLibraryLoader.load("nocturne-attach");
            if (!available) {
                throw new UnsatisfiedLinkError("unix attach native library not available");
            }
        }
    }

    /**
     * 连接目标 JVM 的 attach 监听套接字。
     *
     * @param socketPath 形如 {@code /tmp/.java_pid<pid>} 的路径
     * @return 连接句柄（{@code >= 0}）；失败抛 {@link IOException}
     * @throws IOException 目标不可达、路径不是本用户的套接字、或系统调用失败时抛出
     */
    public static int connect(String socketPath) throws IOException {
        ensureLoaded();
        int fd = nConnect(socketPath);
        if (fd < 0) {
            throw new IOException("cannot connect to " + socketPath + ": "
                    + PosixAttachNative.describe(-fd));
        }
        return fd;
    }

    /**
     * 把请求字节全部写出。
     *
     * @param fd      {@link #connect} 返回的句柄
     * @param request {@link AttachProtocol} 线字节
     * @throws IOException 写失败（对端关闭等）时抛出
     */
    public static void write(int fd, byte[] request) throws IOException {
        ensureLoaded();
        int code = nWrite(fd, request, 0, request.length);
        if (code != 0) {
            throw new IOException("cannot send attach request: " + describe(-code));
        }
    }

    /**
     * 读一段回复。
     *
     * @param fd     {@link #connect} 返回的句柄
     * @param buffer 目标缓冲
     * @param offset 起始偏移
     * @param length 上限长度
     * @return 实际读到的字节数；{@code 0} 表示对端已关闭（读完）
     * @throws IOException 读失败时抛出
     */
    public static int read(int fd, byte[] buffer, int offset, int length) throws IOException {
        ensureLoaded();
        int read = nRead(fd, buffer, offset, length);
        if (read < 0) {
            throw new IOException("cannot read attach reply: " + describe(-read));
        }
        return read;
    }

    /** 关闭连接（可重复调用）。 */
    public static void close(int fd) {
        if (fd < 0) {
            return;
        }
        try {
            ensureLoaded();
            nClose(fd);
        } catch (Throwable ignored) {
            // 关闭失败无法补救，也不能掩盖已成功的 load。
        }
    }

    /**
     * 把 errno 译成可读文本。
     *
     * @param errno 正整数 errno（调用方已取反）
     * @return 形如 {@code EACCES(13)} 的文本
     */
    static String describe(int errno) {
        String name;
        switch (errno) {
            case 2: name = "ENOENT"; break;
            case 13: name = "EACCES"; break;
            case 36: name = "ENAMETOOLONG"; break;
            case 38: name = "ENOSYS"; break;
            case 88: name = "ENOTSOCK"; break;
            case 98: name = "EADDRINUSE"; break;
            case 111: name = "ECONNREFUSED"; break;
            case 12: name = "ENOMEM"; break;
            case 22: name = "EINVAL"; break;
            default: name = "errno"; break;
        }
        return name + "(" + errno + ")";
    }

    private static native int nConnect(String socketPath);

    private static native int nWrite(int fd, byte[] buffer, int offset, int length);

    private static native int nRead(int fd, byte[] buffer, int offset, int length);

    private static native void nClose(int fd);
}
