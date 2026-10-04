package dev.noturne.core.attach;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 枚举本机上正在运行的 JVM 进程，并从中筛出疑似 Minecraft 客户端。
 *
 * <p>刻意基于操作系统自带工具（Windows 下 tasklist / PowerShell，Unix 下 ps）实现，而不用
 * Java 9 的 {@code ProcessHandle}：整个 core 模块必须能在 Java 8 的 JVM 上加载（Minecraft 1.8.9
 * 是受支持的目标），因此不能引用任何 Java 9+ 的类型。
 */
public final class ProcessScanner {

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

        /** UI 用短标签：有窗口标题时用标题，否则用映像名。 */
        public String displayName() {
            return windowTitle.isEmpty() ? image : windowTitle;
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
     * @throws IOException          tasklist 启动或读取失败
     * @throws InterruptedException 等待子进程时被中断
     */
    private static List<ProcessInfo> onWindows() throws IOException, InterruptedException {
        // tasklist 是原生工具，毫秒级启动，且 /V 已自带窗口标题列；PowerShell 在本机实测需要
        // 10 秒至 2 分钟，无法满足 UI 每次刷新都调用的场景。
        List<ProcessInfo> out = new ArrayList<ProcessInfo>();
        String[] images = {"java.exe", "javaw.exe"};
        for (String image : images) {
            List<String> lines = run("tasklist", "/FI", "IMAGENAME eq " + image, "/FO", "CSV", "/NH", "/V");
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
                out.add(new ProcessInfo(
                        Integer.parseInt(pid),
                        image,
                        "",
                        cols.get(8).trim()));
            }
        }
        return out;
    }

    // --------------------------------------------------------------- Unix (ps)

    /**
     * Unix 实现：用 {@code ps -e -o pid=,comm=,args=} 一次性取回 pid、程序名与完整参数。
     *
     * <p>只保留程序名以 {@code java} 开头的行（即 JVM 进程）。
     *
     * @throws IOException          ps 启动或读取失败
     * @throws InterruptedException 等待子进程时被中断
     */
    private static List<ProcessInfo> onUnix() throws IOException, InterruptedException {
        List<String> lines = run("ps", "-e", "-o", "pid=,comm=,args=");
        List<ProcessInfo> out = new ArrayList<ProcessInfo>();
        for (String line : lines) {
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
            String image = sp2 < 0 ? rest : rest.substring(0, sp2);
            String args = sp2 < 0 ? "" : rest.substring(sp2 + 1).trim();
            if (!image.startsWith("java")) {
                continue;
            }
            out.add(new ProcessInfo(Integer.parseInt(pidPart), image, args, ""));
        }
        return out;
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
            for (String line : run("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)) {
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
        } catch (Exception ignored) {
            // 视作「拿不到命令行」，交由调用方降级
        }
        return out;
    }

    /**
     * 子进程输出的解码字符集。
     *
     * <p>Windows 的 tasklist / PowerShell 按控制台代码页输出（简体中文系统即 GBK），用 UTF-8 硬解会把
     * 中文进程名与窗口标题变成乱码；Unix 工具则普遍输出 UTF-8。
     *
     * @return 当前平台下应使用的字符集
     */
    private static Charset consoleCharset() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return StandardCharsets.UTF_8;
        }
        // sun.jnu.encoding 即 Windows 的 ANSI 代码页，正是控制台工具的默认输出编码。
        String encoding = System.getProperty("sun.jnu.encoding");
        if (encoding != null) {
            try {
                return Charset.forName(encoding);
            } catch (Throwable ignored) {
                // 属性值不被识别时退回平台默认，不因一个属性让整个扫描失败。
            }
        }
        return Charset.defaultCharset();
    }

    /**
     * 执行外部命令并收集其标准输出行。
     *
     * <p>把 stderr 合并进 stdout，因为部分系统工具（tasklist）把「无匹配任务」之类的提示写到 stderr，
     * 丢弃它会让上层误以为命令失败。
     *
     * @param command 命令与参数
     * @return 输出的各行（不含行尾）
     * @throws IOException          无法启动命令
     * @throws InterruptedException 等待命令结束时被中断
     */
    private static List<String> run(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        List<String> lines = new ArrayList<String>();
        InputStream in = process.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, consoleCharset()));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            reader.close();
        }
        process.waitFor();
        return lines;
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

    /** 把字符串截断到 {@code max} 个字符，超长时以 {@code ...} 结尾，避免命令行撑爆 UI 行。 */
    private static String abbreviate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }
}
