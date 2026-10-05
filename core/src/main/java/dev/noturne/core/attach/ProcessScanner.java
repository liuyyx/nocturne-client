package dev.noturne.core.attach;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 枚举本机上正在运行的 JVM 进程，并从中筛出疑似 Minecraft 客户端。
 *
 * <p>刻意基于操作系统自带工具（Windows 下 tasklist / PowerShell，Unix 下 ps）实现，而不用
 * Java 9 的 {@code ProcessHandle}：整个 core 模块必须能在 Java 8 的 JVM 上加载（Minecraft 1.8.9
 * 是受支持的目标），因此不能引用任何 Java 9+ 的类型。
 */
public final class ProcessScanner {

    /** tasklist / ps 这类本机工具的正常耗时上限（毫秒）。 */
    private static final long FAST_TIMEOUT_MS = 15000L;

    /** WMI 命令行查询实测需 10 秒至 2 分钟，给一个较宽松但仍有界的上限（毫秒）。 */
    private static final long WMI_TIMEOUT_MS = 180000L;

    /** 提取 chcp 输出中代码页数字的匹配器。 */
    private static final Pattern CODE_PAGE = Pattern.compile("(\\d+)");

    /** 缓存探测到的控制台字符集，避免每次扫描都启动一次辅助进程。 */
    private static volatile Charset cachedConsoleCharset;

    /** 扫描结果条目：一个 JVM 进程的可展示信息。不可变。 */
    public static final class ProcessInfo {
        /** 操作系统进程 id，用于 attach。 */
        public final int pid;
        /** 进程映像名（Windows 下为 {@code java.exe} / {@code javaw.exe}）。 */
        public final String image;
        /** 完整命令行；Windows 的快速扫描拿不到时为空串。 */
        public final String commandLine;
        /** 顶层窗口标题（通常是游戏窗口）；没有窗口时为空串（构造时把 {@code null} 归一化为空串）。 */
        public final String windowTitle;

        /**
         * 构造一个进程条目。
         *
         * @param pid         进程 id
         * @param image       进程映像名
         * @param commandLine 命令行
         * @param windowTitle 窗口标题，可为 {@code null}
         */
        ProcessInfo(int pid, String image, String commandLine, String windowTitle) {
            this.pid = pid;
            this.image = image;
            this.commandLine = commandLine;
            this.windowTitle = windowTitle == null ? "" : windowTitle;
        }

        /**
         * 依据命令行与窗口标题判定该进程是否像 Minecraft 客户端。
         *
         * <p>覆盖官方启动器、KnotClient、Fabric 等多种启动方式，匹配时统一转小写以兼容大小写混杂的路径。
         */
        public boolean isMinecraft() {
            String cmd = commandLine.toLowerCase(Locale.ROOT);
            String title = windowTitle.toLowerCase(Locale.ROOT);
            return cmd.contains("minecraft")
                    || cmd.contains(".minecraft")
                    || cmd.contains("net.minecraft")
                    || cmd.contains("knotclient")
                    || cmd.contains("fabric-loader")
                    || title.contains("minecraft");
        }

        /** UI 用短标签：有窗口标题时用标题（过长则截断），否则用映像名。 */
        public String displayName() {
            String label = windowTitle.isEmpty() ? image : windowTitle;
            // 游戏窗口标题常带长后缀（"X (HEAD - 6697bab)"），列表列宽有限；
            // 截断到可读长度，完整值仍在 display()/toString() 里，便于粘贴到日志。
            return label.length() > 34 ? label.substring(0, 33) + "\u2026" : label;
        }

        /** UI 用完整标签：有窗口标题时输出「标题 + 命令行」，否则只输出截断后的命令行。 */
        public String display() {
            if (!windowTitle.isEmpty()) {
                return windowTitle + "      \u2014      " + abbreviate(commandLine, 90);
            }
            return abbreviate(commandLine, 150);
        }

        /** 返回单行可读摘要，供列表与日志使用。 */
        @Override
        public String toString() {
            return pid + "  " + image + "  " + (windowTitle.isEmpty() ? "" : "[" + windowTitle + "] ") + abbreviate(commandLine, 120);
        }
    }

    /** 工具类，禁止实例化。 */
    private ProcessScanner() {
    }

    /**
     * 列出所有存活的 java / javaw 进程。
     *
     * @return 进程列表；底层命令缺失或失败时返回空列表而非抛出异常（扫描属于尽力而为的辅助能力）
     */
    public static List<ProcessInfo> javaProcesses() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return onWindows();
            }
            return onUnix();
        } catch (InterruptedException e) {
            // 恢复中断标志，避免调用方（SwingWorker）取消后后台线程仍在跑
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 在 {@link #javaProcesses()} 的结果中过滤出看起来像 Minecraft 的进程。
     *
     * @return 命中的进程列表，可能为空
     */
    public static List<ProcessInfo> minecraftProcesses() {
        List<ProcessInfo> out = new ArrayList<ProcessInfo>();
        for (ProcessInfo p : javaProcesses()) {
            if (p.isMinecraft()) {
                out.add(p);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Windows

    /**
     * Windows 实现：分别查询 {@code java.exe} 与 {@code javaw.exe}。
     *
     * <p>这里刻意不取命令行（那需要 WMI，耗时以秒计），因此返回条目的 {@code commandLine} 为空串，
     * 完整命令行由 {@link #javaProcessCommandLines()} 在后台线程补齐。
     *
     * <p>两个映像名分别查询：其中一个失败不影响另一个已取得的结果。
     *
     * @throws IOException         两个查询都失败时抛出最后一次失败原因
     * @throws InterruptedException 等待子进程时被中断
     */
    private static List<ProcessInfo> onWindows() throws IOException, InterruptedException {
        // tasklist 是原生工具，毫秒级启动，且 /V 已自带窗口标题列；PowerShell 在本机实测需要
        // 10 秒至 2 分钟，无法满足 UI 每次刷新都调用的场景。
        List<ProcessInfo> out = new ArrayList<ProcessInfo>();
        String[] images = {"java.exe", "javaw.exe"};
        IOException failure = null;
        for (String image : images) {
            List<String> lines;
            try {
                lines = run(FAST_TIMEOUT_MS, "tasklist", "/FI", "IMAGENAME eq " + image, "/FO", "CSV", "/NH", "/V");
            } catch (IOException e) {
                // 单个映像名查询失败不应丢弃另一个已经拿到（或稍后拿到）的结果。
                if (failure == null) {
                    failure = e;
                }
                continue;
            }
            for (String line : lines) {
                List<String> cols = parseCsvLine(line);
                // Image Name, PID, Session Name, Session#, Mem Usage, Status, User, CPU Time, Window Title
                if (cols.size() < 9) {
                    continue;
                }
                String pid = cols.get(1).trim();
                if (!isDigits(pid)) {
                    continue; // localised "no tasks" message, or a malformed row
                }
                Integer pidValue = parsePidSafely(pid);
                if (pidValue == null) {
                    continue; // 溢出/非正数不算 fatal，跳过该行即可，不能打断整个扫描
                }
                out.add(new ProcessInfo(
                        pidValue.intValue(),
                        normalizeTitle(cols.get(0)),   // 真的 Image Name 列，不再用循环变量
                        "",
                        normalizeTitle(cols.get(8))));
            }
        }
        if (out.isEmpty() && failure != null) {
            throw failure;
        }
        return out;
    }

    /** {@code tasklist /V} 对没有窗口的进程输出字面量 {@code N/A}；它不该被当成真实标题展示。 */
    static String normalizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("N/A")) {
            return "";
        }
        return trimmed;
    }

    // --------------------------------------------------------------- Unix (ps)

    /**
     * Unix 实现：用 {@code ps -e -o pid=,comm=,args=} 一次性取回 pid、程序名与完整参数。
     *
     * <p>只保留程序名（basename）以 {@code java} 开头的行（即 JVM 进程）。macOS/BSD 的
     * {@code comm} 是可执行文件<em>完整路径</em>（如 {@code /Library/Java/.../bin/java}），
     * Linux 则只是被截断到 15 字符的 basename，因此必须先取 basename 再判断。
     *
     * @throws IOException          ps 启动或读取失败
     * @throws InterruptedException 等待子进程时被中断
     */
    private static List<ProcessInfo> onUnix() throws IOException, InterruptedException {
        // -ww：不加它 ps 在管道输出下会按 80 列截断 args（JDK 8 目标 JVM 的 -cp 很长），
        // javadoc 声称的「完整参数」从此变成谎言。BSD 与 procps 都接受 -ww。
        List<String> lines = run(FAST_TIMEOUT_MS, "ps", "-e", "-ww", "-o", "pid=,ucomm=,args=");
        List<ProcessInfo> out = new ArrayList<ProcessInfo>();
        for (String line : lines) {
            // ucomm 是不含路径的短可执行名（macOS 与 Linux 都是 basename，无空格），
            // 因此第一列之后就是它，再往后才是 args —— 这里绕开了 macOS comm 全路径含空格
            // 导致按空格切分腰斩的问题（修复前的 critical 缺陷，见此注释归档）。
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int sp1 = trimmed.indexOf(' ');
            if (sp1 < 0) {
                continue;
            }
            String pidPart = trimmed.substring(0, sp1).trim();
            if (!isDigits(pidPart)) {
                continue;
            }
            String rest = trimmed.substring(sp1 + 1).trim();
            int sp2 = rest.indexOf(' ');
            String ucomm = sp2 < 0 ? rest : rest.substring(0, sp2);
            String args = sp2 < 0 ? "" : rest.substring(sp2 + 1).trim();
            // 精确匹配 java/javaw（大小写不敏感），既不放 javac/javadoc，也不丢 /Library/Java/…
            // 下部行和名字被截断的 Java 进程。修复前用 startsWith("java")，两端的误收/误删全踩。
            String base = ucomm.toLowerCase(Locale.ROOT);
            if (!base.equals("java") && !base.equals("javaw")) {
                continue;
            }
            Integer pid = parsePidSafely(pidPart);
            if (pid == null) {
                continue;
            }
            out.add(new ProcessInfo(pid.intValue(), ucomm, args, ""));
        }
        return out;
    }

    /** 把 pid 字符串安全转成 int：超 int 范围时返回 null 而不是抛 NumberFormatException 打断整个扫描。 */
    private static Integer parsePidSafely(String pidPart) {
        try {
            long value = Long.parseLong(pidPart);
            return (value > 0L && value <= Integer.MAX_VALUE) ? Integer.valueOf((int) value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取路径的最后一段（仅 {@code /}；Unix 上 {@code \} 是合法文件名字符，Windows 分隔符残留是 bug）。 */
    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }


    // ------------------------------------------------------------------ helpers

    /**
     * 取回每个 java/javaw 进程的完整命令行，按 pid 索引（仅 Windows）。
     *
     * <p>与 {@link #javaProcesses()} 分开是因为它需要走 WMI，耗时以秒计 —— 调用方应在 UI 线程之外
     * 执行，再把结果合并回来。非 Windows 或 WMI 被禁用时返回空 map，调用方应视作「拿不到命令行」。
     *
     * @return pid 到命令行的映射；不可用时为空
     */
    public static java.util.Map<Integer, String> javaProcessCommandLines() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return Collections.emptyMap();
        }
        String script = "Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\""
                + " | ForEach-Object { $_.ProcessId.ToString() + '|' + $_.CommandLine }";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        // 用 UTF-16LE + Base64 传脚本：避免命令行转义与本地代码页导致的乱码。
        java.util.Map<Integer, String> out = new java.util.HashMap<Integer, String>();
        try {
            for (String line : run(WMI_TIMEOUT_MS, "powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)) {
                int separator = line.indexOf('|');
                if (separator <= 0) {
                    continue;
                }
                try {
                    out.put(Integer.parseInt(line.substring(0, separator).trim()),
                            line.substring(separator + 1));
                } catch (NumberFormatException ignored) {
                    // 不是进程行（PowerShell 的其他输出），忽略
                }
            }
        } catch (InterruptedException e) {
            // 恢复中断标志；SwingWorker 取消后不该继续跑
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 视作「拿不到命令行」，交由调用方降级
        }
        return out;
    }

    /**
     * 子进程输出的解码字符集。
     *
     * <p>Windows 的 tasklist / PowerShell 按<em>控制台（OEM）代码页</em>输出（简体中文系统即 GBK），
     * 用 UTF-8 硬解会把中文进程名与窗口标题变成乱码；Unix 工具则普遍输出 UTF-8。
     *
     * <p>不能依赖 {@code sun.jnu.encoding}：JEP 400 之后它不再是可靠的平台编码来源。这里优先直接
     * 探测控制台代码页（{@code chcp}），失败再退回 {@code native.encoding} / {@code sun.jnu.encoding}。
     *
     * @return 当前平台下应使用的字符集
     */
    private static Charset consoleCharset() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return StandardCharsets.UTF_8;
        }
        Charset cached = cachedConsoleCharset;
        if (cached != null) {
            return cached;
        }
        Charset resolved = resolveWindowsConsoleCharset();
        cachedConsoleCharset = resolved;
        return resolved;
    }

    /** 探测 Windows 控制台字符集：优先 OEM 代码页，再退回平台属性。 */
    private static Charset resolveWindowsConsoleCharset() {
        Integer codePage = queryConsoleCodePage();
        if (codePage != null) {
            Charset byCodePage = charsetForCodePage(codePage.intValue());
            if (byCodePage != null) {
                return byCodePage;
            }
        }
        // JEP 400（JDK 17+）暴露的平台原生编码
        Charset nativeEncoding = charsetOrNull(System.getProperty("native.encoding"));
        if (nativeEncoding != null) {
            return nativeEncoding;
        }
        Charset jnu = charsetOrNull(System.getProperty("sun.jnu.encoding"));
        if (jnu != null) {
            return jnu;
        }
        return Charset.defaultCharset();
    }

    /** 执行 {@code chcp} 并解析当前控制台代码页；失败返回 {@code null}。 */
    private static Integer queryConsoleCodePage() {
        Process process = null;
        try {
            process = new ProcessBuilder("cmd", "/c", "chcp").redirectErrorStream(true).start();
            // chcp 的输出只含 ASCII 文案与数字，用 US-ASCII 读取即可，不会与 consoleCharset 递归。
            InputStream in = process.getInputStream();
            byte[] buffer = new byte[256];
            int total = 0;
            int read;
            while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
                total += read;
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                return null;
            }
            String text = new String(buffer, 0, total, StandardCharsets.US_ASCII);
            Matcher matcher = CODE_PAGE.matcher(text);
            Integer last = null;
            while (matcher.find()) {
                last = Integer.valueOf(matcher.group(1));
            }
            return last;
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** 把 Windows 代码页映射到 Java 字符集；未知或不受支持时返回 {@code null}。 */
    private static Charset charsetForCodePage(int codePage) {
        if (codePage == 65001) {
            return StandardCharsets.UTF_8;
        }
        return charsetOrNull("Cp" + codePage);
    }

    /** 按名字解析字符集；为 {@code null}/空或不受支持时返回 {@code null}。 */
    private static Charset charsetOrNull(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return Charset.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 执行外部命令并收集其标准输出行。
     *
     * <p>把 stderr 合并进 stdout，因为部分系统工具（tasklist）把「无匹配任务」之类的提示写到 stderr，
     * 丢弃它会让上层误以为命令失败。
     *
     * <p>读取放在独立的守护线程里，主线程只等待至多 {@code timeoutMillis}：命令卡死时子进程会被
     * 强制销毁并抛出 {@link IOException}，而不是让 SwingWorker 永不结束、留下孤儿进程。
     *
     * @param timeoutMillis 等待子进程退出的上限（毫秒）
     * @param command       命令与参数
     * @return 输出的各行（不含行尾）
     * @throws IOException          无法启动命令、读取失败或命令超时
     * @throws InterruptedException 等待命令结束时被中断
     */
    private static List<String> run(long timeoutMillis, String... command) throws IOException, InterruptedException {
        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        final Charset charset = consoleCharset();
        final List<String> lines = new ArrayList<String>();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(process.getInputStream(), charset));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (lines) {
                            lines.add(line);
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被强制销毁时读端报错属预期，交由主线程的超时逻辑处理
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException ignored) {
                            // 关闭失败无需上报
                        }
                    }
                }
            }
        }, "noturne-process-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
        if (!finished) {
            process.destroyForcibly();
            reader.interrupt();
            throw new IOException("command timed out after " + timeoutMillis + "ms: " + Arrays.toString(command));
        }
        // 进程已退出，给读线程一点时间消费完管道缓冲（有界，避免异常场景卡死）
        reader.join(2000L);
        synchronized (lines) {
            return new ArrayList<String>(lines);
        }
    }

    /** 最小化的 RFC-4180 CSV 字段切分器：处理引号包裹、{@code ""} 双写转义与 CRLF 行尾。 */
    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(field.toString());
                field.setLength(0);
            } else if (c != '\r') {
                field.append(c);
            }
        }
        out.add(field.toString());
        return out;
    }

    /** 判断字符串是否非空且全部为 ASCII 数字（用于校验进程 id 列）。 */
    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 把字符串截断到 {@code max} 个字符，超长时以 {@code ...} 结尾，避免命令行撑爆 UI 行。
     *
     * @param s   原始字符串，可为 {@code null}
     * @param max 最多保留的字符数；{@code <= 3} 时直接截断，不再追加省略号（否则会 {@code substring(0, 负数)} 越界）
     */
    private static String abbreviate(String s, int max) {
        if (s == null || max <= 0) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        if (max <= 3) {
            return s.substring(0, max);
        }
        return s.substring(0, max - 3) + "...";
    }
}
