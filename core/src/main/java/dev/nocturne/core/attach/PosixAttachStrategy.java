package dev.nocturne.core.attach;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Unix（Linux / macOS）自实现 attach 通道（自研实现，仅行为对齐 HotSpot）。
 *
 * <p>流程（与 {@code jdk-api} 策略等价，但全程不碰 {@code jdk.attach}）：
 * <ol>
 *   <li>拼出目标的 attach 监听套接字路径 {@code <tmpdir>/.java_pid<pid>}；</li>
 *   <li>连接它（原生层会先校验该路径确实是<b>本用户</b>的套接字文件，与 HotSpot 发起端一致）；</li>
 *   <li>写入 {@link AttachProtocol} 线字节（5 个 NUL 结尾字符串），读到对端关闭；</li>
 *   <li>按返回码映射为异常或成功。连接关闭即 detach，agent 留在目标里。</li>
 * </ol>
 *
 * <p>约束与 HotSpot 一致：同机同用户、目标是未禁 attach 的 HotSpot、目标已创建监听套接字
 * （即目标启动后曾有人请求过 attach，或目标以 {@code -XX:+StartAttachListener} 启动）。
 *
 * <p><b>验证状态</b>：Windows 通道已实机验证（1.8.9 真机 + 裁剪 JRE）；本类尚未在
 * Linux/macOS 实机验证——本机（Windows）既无法编译出 {@code .so}/{@code .dylib}，
 * 也无法运行 Linux JVM。已完成的验证只有：WSL 中 {@code cc} 编译通过 + 逻辑级联调。
 */
public final class PosixAttachStrategy implements AttachStrategy {

    /** 策略名，用于诊断输出。 */
    public static final String STRATEGY_NAME = "unix-domain";

    /** attach 套接字文件名前缀（HotSpot 约定）。 */
    private static final String SOCKET_PREFIX = ".java_pid";

    /** 读回复时单次读取的上限。 */
    private static final int READ_CHUNK = 512;

    /** 回复文本上限（防御畸形目标无限输出）。 */
    private static final int MAX_REPLY_CHARS = 64 * 1024;

    /** 回退目录：目标侧 TMPDIR 未被发起端继承时，HotSpot 默认仍在 /tmp 下建套接字。 */
    private static final String FALLBACK_TMP = "/tmp";

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
     * @throws Exception 连接失败、目标拒绝或 agent 启动失败时抛出
     */
    @Override
    public void attach(int pid, File agentJar, String options) throws Exception {
        if (!agentJar.isFile()) {
            throw new IllegalArgumentException("agent jar not found: " + agentJar);
        }
        if (pid <= 0) {
            throw new IllegalArgumentException("bad pid: " + pid);
        }
        String primary = socketPath(System.getProperty("java.io.tmpdir"), pid);
        IOException failure;
        try {
            attachVia(primary, pid, agentJar, options);
            return;
        } catch (IOException e) {
            failure = e;
        }
        // 目标侧的 TMPDIR 未必与发起端相同：HotSpot 默认在 /tmp 下建监听套接字，
        // 主路径不存在时再试一次 /tmp，避免"路径不同就永远挂不上"。
        String fallback = socketPath(FALLBACK_TMP, pid);
        if (fallback.equals(primary)) {
            throw failure;
        }
        try {
            attachVia(fallback, pid, agentJar, options);
        } catch (IOException e) {
            e.addSuppressed(failure);
            throw e;
        }
    }

    /** 走一次完整的「连接 → 写 → 读 → 判定」；失败抛 {@link IOException}。 */
    private static void attachVia(String socketPath, int pid, File agentJar, String options)
            throws Exception {
        int fd = PosixAttachNative.connect(socketPath);
        try {
            PosixAttachNative.write(fd, AttachProtocol.encodeLoad(agentJar.getAbsolutePath(),
                    options, false));
            String reply = readReply(fd);
            WindowsAttachStrategy.checkReply(reply, pid);
        } finally {
            PosixAttachNative.close(fd);
        }
    }

    /** 读到对端关闭为止，把回复拼成 UTF-8 文本。 */
    private static String readReply(int fd) throws IOException {
        StringBuilder out = new StringBuilder(256);
        byte[] buffer = new byte[READ_CHUNK];
        while (true) {
            int read = PosixAttachNative.read(fd, buffer, 0, buffer.length);
            if (read == 0) {
                break;
            }
            out.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
            if (out.length() > MAX_REPLY_CHARS) {
                throw new IOException("attach reply too large (> " + MAX_REPLY_CHARS + " chars)");
            }
        }
        return out.toString();
    }

    /**
     * 拼出 attach 监听套接字路径。
     *
     * @param tmpDir 目录（可为 {@code null}/空，按 {@code /tmp} 处理）
     * @param pid    目标进程 id
     * @return 形如 {@code /tmp/.java_pid1234} 的路径（结尾多余的目录分隔符会被去掉）
     */
    static String socketPath(String tmpDir, int pid) {
        String base = (tmpDir == null || tmpDir.isEmpty()) ? FALLBACK_TMP : tmpDir;
        while (base.length() > 1 && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + SOCKET_PREFIX + pid;
    }
}
