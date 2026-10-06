package dev.nocturne.core.attach;

import java.io.IOException;

/**
 * Windows attach 原生通道声明（自研重写，仅行为对齐 HotSpot）。
 *
 * <p>生产实现是随 core 一起编译的 JNI 小库（{@code core/src/main/native/windows/attach.c}，
 * Gradle 用本机 MSVC 现地编译为 {@code nocturne-attach.dll}，只在 Windows-x64 上构建，
 * 其他平台跳过）。库在首次调用时从 jar 资源解压到临时目录加载；加载失败时所有入口抛
 * {@link UnsatisfiedLinkError}，策略层按“本策略不可用、交给链上其他策略”处理——
 * 绝不伪装成功，也绝不静默跳过。
 *
 * <p>需要的原生动作（与 OpenJDK 发起端等价，但全部自研实现）：
 * <ul>
 *   <li>{@code openProcess}：按 pid 打开进程句柄（查询 + 写内存权限）；</li>
 *   <li>{@code createPipeServer}：建本次命令专用的服务端命名管道（返回管道句柄，
 *      Java 侧包装为 {@link WindowsAttachStrategy.PipeServer}）；</li>
 *   <li>{@code enqueueOperation}：经远线程桩把 attach 操作送进目标 JVM
 *      （桩代码与目标架构同位宽，x64 自包含汇编）；</li>
 *   <li>{@code closeProcess}：关闭进程句柄。</li>
 * </ul>
 */
public final class WindowsAttachNative {

    /** 工具类，禁止实例化。 */
    private WindowsAttachNative() {
    }

    /** 原生库是否已尝试加载（只试一次，失败即永久放弃，避免每 attach 打一次 IO）。 */
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
                throw new UnsatisfiedLinkError("windows attach native library not available");
            }
            return;
        }
        synchronized (WindowsAttachNative.class) {
            if (loadAttempted) {
                if (!available) {
                    throw new UnsatisfiedLinkError("windows attach native library not available");
                }
                return;
            }
            loadAttempted = true;
            available = NativeLibraryLoader.load("nocturne-attach");
            if (!available) {
                throw new UnsatisfiedLinkError("windows attach native library not available");
            }
        }
    }

    /**
     * 按 pid 打开目标进程。
     *
     * @param pid 目标进程 id
     * @return 进程句柄（0 表示打不开，调用方按“进程不存在或无权限”报告）
     * @throws IOException 打开过程中发生可报告的系统错误时抛出
     */
    public static long openProcess(int pid) throws IOException {
        ensureLoaded();
        return nOpenProcess(pid);
    }

    /** 关闭 {@link #openProcess} 返回的句柄。 */
    public static void closeProcess(long handle) throws IOException {
        ensureLoaded();
        nCloseProcess(handle);
    }

    /**
     * 建本次命令专用的服务端命名管道。
     *
     * @param pipeName 形如 {@code \\.\pipe\javatool<pid>-<随机数>} 的全名
     * @return 已建好的服务端（调用方负责等待连入与关闭）
     * @throws IOException 建管道失败时抛出
     */
    public static WindowsAttachStrategy.PipeServer createPipeServer(String pipeName)
            throws IOException {
        ensureLoaded();
        long pipeHandle = nCreatePipeServer(pipeName);
        return new NativePipeServer(pipeHandle);
    }

    /**
     * 把 attach 操作送进目标 JVM（经远线程桩调用目标的入队函数）。
     *
     * @param handle   目标进程句柄
     * @param request  {@link AttachProtocol} 线字节
     * @param pipeName 本次命令的服务端管道名（随操作一起送达，供目标回连）
     * @throws IOException 投递失败时抛出
     */
    public static void enqueueOperation(long handle, byte[] request, String pipeName)
            throws IOException {
        ensureLoaded();
        int code = nEnqueueOperation(handle, request, pipeName);
        if (code == 127) {
            // 借用的 ERROR_PROC_NOT_FOUND：目标内无 jvm.dll 或非 x64，属“挂不上”而非传输错。
            throw new IOException("cannot locate JVM_EnqueueOperation in target "
                    + "(not a 64-bit HotSpot JVM?)");
        }
        if (code != 0) {
            throw new IOException("enqueue attach operation failed: " + code);
        }
    }

    private static native long nOpenProcess(int pid);

    private static native void nCloseProcess(long handle);

    private static native long nCreatePipeServer(String pipeName);

    private static native int nEnqueueOperation(long handle, byte[] request, String pipeName);
}
