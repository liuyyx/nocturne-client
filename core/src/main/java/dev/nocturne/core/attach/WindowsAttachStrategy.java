package dev.nocturne.core.attach;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/**
 * Windows 自实现 attach 发起端（自研重写，仅行为对齐 HotSpot）。
 *
 * <p>流程（与 {@code jdk-api} 策略等价，但全程不碰 {@code jdk.attach} 模块）：
 * <ol>
 *   <li>用进程句柄向目标 JVM 的 attach 监听器投递 {@code load} 操作
 *      （{@link WindowsAttachNative} 经由 {@code JVM_EnqueueOperation} 桩）；</li>
 *   <li>监听器执行完成后连回本次命令专用的服务端命名管道
 *       （{@code \\.\pipe\javatool<pid>-<随机数>}，JDK 21 起带一次碰撞重试）；</li>
 *   <li>按 {@link AttachProtocol} 读外层返回码 + load 内层结果，映射为异常或成功；</li>
 *   <li>关闭进程句柄（detach 只释放发起端资源，agent 留在目标里）。</li>
 * </ol>
 *
 * <p>约束与 HotSpot 一致：同机同用户（或管理员）、目标是未禁 attach 的 HotSpot、
 * JDK 21+ 拒绝自 attach（除非目标允许）。任一不满足都按 {@link AttachException}
 * 的口径向上报告，不静默。
 */
public final class WindowsAttachStrategy implements AttachStrategy {

    /** 策略名，用于诊断输出。 */
    public static final String STRATEGY_NAME = "windows-native";

    /** 管道名中随机数的上限（与 HotSpot 的重试语义对齐，冲突时换一个再试一次）。 */
    private static final int PIPE_RANDOM_BOUND = 1 << 24;

    /** 连接目标监听器输出管道的等待上限（毫秒）。 */
    private static final long PIPE_CONNECT_TIMEOUT_MS = 5000L;

    @Override
    public String name() {
        return STRATEGY_NAME;
    }

    /**
     * 把 agent jar 加载进 pid 指定的 JVM。
     *
     * @param pid      目标进程 id
     * @param agentJar agent jar 路径
     * @param options  传给 {@code agentmain} 的参数字串，可为 {@code null}
     * @throws Exception 传输失败、目标拒绝或 agent 启动失败时抛出
     */
    @Override
    public void attach(int pid, File agentJar, String options) throws Exception {
        if (!agentJar.isFile()) {
            throw new IllegalArgumentException("agent jar not found: " + agentJar);
        }
        if (pid <= 0) {
            throw new IllegalArgumentException("bad pid: " + pid);
        }
        long handle = WindowsAttachNative.openProcess(pid);
        if (handle == 0L) {
            throw new IOException("cannot open process " + pid
                    + " (not running, or access denied)");
        }
        try {
            executeLoad(handle, pid, agentJar.getAbsolutePath(), options);
        } finally {
            WindowsAttachNative.closeProcess(handle);
        }
    }

    /**
     * 执行一次 load：建服务端管道 → 投递操作 → 读回结果 → 映射异常。
     *
     * @param handle   目标进程句柄（调用方负责关闭）
     * @param pid      目标进程 id（仅用于诊断与管道名）
     * @param agentJar agent jar 绝对路径
     * @param options  agent 参数，可为 {@code null}
     * @throws Exception 目标拒绝或 agent 启动失败时抛出
     */
    private static void executeLoad(long handle, int pid, String agentJar, String options)
            throws Exception {
        Random random = new Random();
        IOException lastPipeFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String pipeName = "\\\\.\\pipe\\javatool" + pid + "-" + random.nextInt(PIPE_RANDOM_BOUND);
            PipeServer server = null;
            try {
                server = WindowsAttachNative.createPipeServer(pipeName);
            } catch (IOException pipeFailure) {
                lastPipeFailure = pipeFailure;
                continue;
            }
            try {
                byte[] request = AttachProtocol.encodeLoad(agentJar, options, false);
                WindowsAttachNative.enqueueOperation(handle, request, pipeName);
                server.awaitConnection(PIPE_CONNECT_TIMEOUT_MS);
                String reply = readReply(server);
                checkReply(reply, pid);
                return;
            } catch (AttachOperationException fatal) {
                throw fatal.asException(pid);
            } finally {
                closeQuietly(server);
            }
        }
        throw new IOException("cannot create attach pipe for pid " + pid
                + (lastPipeFailure == null ? "" : ": " + lastPipeFailure.getMessage()));
    }

    /** 从已连入的管道读完目标回复（UTF-8 文本，按行切分由调用方处理）。 */
    private static String readReply(PipeServer server) throws IOException {
        InputStream in = server.input();
        StringBuilder out = new StringBuilder(256);
        byte[] buffer = new byte[512];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /**
     * 按协议映射回复：外层非 0 即失败；load 内层非 0 即 agent 启动失败。
     *
     * @param reply 目标回复全文
     * @param pid   目标进程 id（仅用于诊断文本）
     * @throws AttachOperationException 携带返回码的结构化失败
     */
    static void checkReply(String reply, int pid) throws AttachOperationException {
        if (reply == null || reply.isEmpty()) {
            throw new AttachOperationException(-1, -1, "empty reply from target JVM");
        }
        int newline = reply.indexOf('\n');
        String first = newline < 0 ? reply : reply.substring(0, newline);
        String rest = newline < 0 ? "" : reply.substring(newline + 1);
        int outer = AttachProtocol.parseReplyCode(first);
        if (outer != AttachProtocol.REPLY_OK) {
            throw new AttachOperationException(outer, -1,
                    "target rejected attach operation: " + outer);
        }
        int inner = AttachProtocol.parseLoadResult(rest);
        if (inner != 0) {
            throw new AttachOperationException(outer, inner,
                    "agent failed to start in target JVM: " + inner);
        }
    }

    /** 安静关闭管道服务端（detach 失败不能掩盖已成功的 load）。 */
    private static void closeQuietly(PipeServer server) {
        if (server == null) {
            return;
        }
        try {
            server.close();
        } catch (Throwable ignored) {
            // detach 失败不能掩盖已成功的 load。
        }
    }

    /**
     * 目标操作失败的结构化携带：外层返回码 + load 内层返回码 + 文本。
     */
    static final class AttachOperationException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int outer;
        private final int inner;

        AttachOperationException(int outer, int inner, String message) {
            super(message);
            this.outer = outer;
            this.inner = inner;
        }

        /** 按返回码映射为对用户可读的异常（与 jdk-api 策略的口径一致）。 */
        Exception asException(int pid) {
            if (outer == AttachProtocol.TARGET_DISABLED) {
                return new IOException("attach mechanism disabled in target JVM (pid " + pid + ")");
            }
            if (outer == AttachProtocol.TARGET_RESOURCE_EXHAUSTED) {
                return new IOException("target JVM out of attach resources (pid " + pid + ")");
            }
            if (outer == AttachProtocol.TARGET_ILLEGAL_ARGUMENT) {
                return new IllegalArgumentException("target rejected attach arguments (pid " + pid
                        + "): " + getMessage());
            }
            if (inner == -4) {
                return new IOException("agent failed to start: out of memory in target");
            }
            if (inner == 100) {
                return new IOException("agent jar not found on target: " + getMessage());
            }
            if (inner == 101) {
                return new IOException("agent class not on target classpath: " + getMessage());
            }
            if (inner == 102) {
                return new IOException("agent failed to start (agentmain threw): " + getMessage());
            }
            return new IOException("attach to pid " + pid + " failed: " + getMessage());
        }
    }

    /**
     * 一次性服务端命名管道（测试可替身，生产走 {@link WindowsAttachNative}）。
     */
    interface PipeServer extends AutoCloseable {
        /** 等待目标连入，超时抛 {@link IOException}。 */
        void awaitConnection(long timeoutMs) throws IOException;

        /** 已连入连接的输入流。 */
        InputStream input();

        @Override
        void close() throws IOException;
    }
}
