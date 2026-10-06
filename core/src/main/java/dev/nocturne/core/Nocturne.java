package dev.nocturne.core;

import dev.nocturne.core.attach.AgentOptions;
import dev.nocturne.core.attach.AttachException;
import dev.nocturne.core.attach.Attacher;
import dev.nocturne.core.attach.CodeSources;
import dev.nocturne.core.attach.CurrentProcess;
import dev.nocturne.core.attach.ProcessScanner;
import dev.nocturne.core.attach.TargetHints;
import dev.nocturne.core.attach.ToolsJarBootstrap;

import java.io.File;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * loader jar 的命令行入口。
 *
 * <p>双击运行 / {@code java -jar} 时：扫描正在运行的 Minecraft 进程，选择一个进行 attach，
 * 并把本 jar 作为 agent 注入。当本 jar 改为以 Fabric / Forge / NeoForge 模组形式加载，
 * 或通过 {@code -javaagent} 加载时，则由相应的 loader 入口接管，不进入此处的流程。
 */
public final class Nocturne {

    /** 运行期失败（找不到目标进程、attach 失败）的退出码。 */
    private static final int EXIT_FAILURE = 1;
    /** 命令行用法错误的退出码（{@code --pid=} 值非法）。 */
    private static final int EXIT_USAGE = 2;
    /** {@code --pid=} 未指定时的哨兵值。 */
    private static final int PID_UNSPECIFIED = -1;
    /** {@code --pid=} 的值无法解析为整数时的哨兵值。 */
    private static final int PID_INVALID = -2;

    /** 工具类，禁止实例化。 */
    private Nocturne() {
    }

    /**
     * 命令行主入口：完成工具 jar 自举、目标进程选择与 agent 注入。
     *
     * @param args 命令行参数，支持 {@code --list-json}（输出进程列表后退出）与
     *             {@code --pid=<n>}（显式指定目标进程）
     * @throws Exception 扫描进程或读取自身 jar 路径失败时抛出；attach 失败则直接以退出码 1 结束
     */
    public static void main(String[] args) throws Exception {
        // JDK 8 下 attach API 位于 tools.jar：需要用带 tools.jar 的新 classpath 重启一次本进程，
        // 返回 true 表示新进程已启动，当前进程应立刻退出。
        if (ToolsJarBootstrap.relaunchIfNeeded("dev.nocturne.core.Nocturne", args)) {
            return;
        }
        int pid = parsePid(args);
        if (pid == PID_INVALID) {
            System.err.println("[nocturne] invalid --pid value; expected a positive integer");
            System.exit(EXIT_USAGE);
        }

        if (hasFlag(args, "--list-json")) {
            printProcessesAsJson();
            return;
        }

        int selfPid = CurrentProcess.pid();
        if (pid != PID_UNSPECIFIED) {
            if (pid <= 0) {
                System.err.println("[nocturne] invalid --pid value " + pid + "; expected a positive integer");
                System.exit(EXIT_USAGE);
            }
            if (CurrentProcess.isSelf(pid)) {
                System.err.println("[nocturne] refusing to attach to this JVM itself (pid " + pid + ")");
                System.exit(EXIT_FAILURE);
            }
        }

        File self = selfJar();

        // 排除自身 pid：本 jar 常被放在 .minecraft/mods 下，扫描进程的路径/标题同样含 "minecraft"，
        // 不排除就会把客户端注入到注入器自己的 JVM 里。
        List<ProcessScanner.ProcessInfo> candidates = withoutSelf(ProcessScanner.minecraftProcesses(), selfPid);
        if (pid == PID_UNSPECIFIED) {
            if (candidates.isEmpty()) {
                StringBuilder message = new StringBuilder("No Minecraft process found.\n\nLive JVMs:\n");
                List<ProcessScanner.ProcessInfo> all = withoutSelf(ProcessScanner.javaProcesses(), selfPid);
                if (all.isEmpty()) {
                    message.append("    (none)\n");
                } else {
                    for (ProcessScanner.ProcessInfo p : all) {
                        message.append("    ").append(p).append('\n');
                    }
                }
                message.append("\nStart Minecraft first, then run this jar again.");
                notifyUser(message.toString());
                System.exit(EXIT_FAILURE);
            }
            ProcessScanner.ProcessInfo chosen = candidates.get(0);
            pid = chosen.pid;
            System.out.println("[nocturne] target: " + chosen);
        }

        System.out.println("[nocturne] attaching to pid " + pid + " with " + self.getName());
        // 版本由注入侧判定并随选项传给 agent（运行时零探测）：命令行里既有 --version 也有
        // versions/<实例>/ 路径。拿不到命令行（非 Windows / WMI 被禁用）时传空串，agent 侧按未知处理。
        String commandLine = ProcessScanner.javaProcessCommandLines().get(Integer.valueOf(pid));
        String options = AgentOptions.composeVersion(AgentOptions.familyFromCommandLine(commandLine));
        // CLI 路径不传 guiKey，agent 侧用默认键；已知目标客户端占用同一个键时提前说明，
        // 否则玩家按下去看到的是对方的面板，会以为我们的注入没生效。
        String keyHint = TargetHints.rightShiftConflict(commandLine, TargetHints.DEFAULT_GUI_KEY_VK);
        if (keyHint != null) {
            System.out.println("[nocturne] note: " + keyHint);
        }
        String strategy;
        try {
            strategy = Attacher.attach(pid, self, options);
        } catch (AttachException e) {
            System.err.println("[nocturne] attach failed: " + e.getMessage());
            // 针对最常见的失败（attach API 不在 classpath 上）给出可操作的提示。
            Throwable cause = e.getCause();
            if (cause instanceof ClassNotFoundException
                    && String.valueOf(cause.getMessage()).contains("com.sun.tools.attach")) {
                System.err.println("[nocturne] neither the JDK attach API nor the native channel worked:");
                System.err.println("[nocturne]   - JDK 8: run with tools.jar on the classpath (JDK, not JRE),");
                System.err.println("[nocturne]   - JDK 9+: the jdk.attach module must be present,");
                System.err.println("[nocturne]   - native channel: needs an x64 HotSpot target this process can open.");
            } else if (cause != null) {
                System.err.println("[nocturne] cause: " + cause);
            }
            System.exit(EXIT_FAILURE);
            // System.exit 不会返回，但 javac 不把它当作 noreturn：
            // 没有这条 return，下面的 strategy 会被判为「可能尚未初始化」。
            return;
        }
        System.out.println("[nocturne] attach strategy: " + strategy);
        System.out.println("[nocturne] agent loaded");
    }

    /** 判断参数列表中是否存在指定开关（如 {@code --list-json}）。 */
    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /** 把候选进程以 {@code [{"pid":…,"title":…,"command":…}]} 的形式输出到标准输出，供外部 UI 解析。 */
    private static void printProcessesAsJson() {
        // 只输出 Minecraft 进程；找不到时输出空数组，绝不退回「全部 JVM」——
        // 启动器把列表当作可注入目标，混入无关 JVM 会导致对错误进程注入并误报成功。
        // 同样排除自身 pid。
        List<ProcessScanner.ProcessInfo> processes = withoutSelf(ProcessScanner.minecraftProcesses(), CurrentProcess.pid());
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < processes.size(); i++) {
            ProcessScanner.ProcessInfo info = processes.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"pid\":").append(info.pid)
                    .append(",\"title\":\"").append(escapeJson(info.windowTitle))
                    .append("\",\"command\":\"").append(escapeJson(info.commandLine))
                    .append("\"}");
        }
        json.append(']');
        // 固定 UTF-8：启动器按 UTF-8 解码，不能随 JVM 默认编码漂移（否则中文窗口标题乱码）。
        writeStdoutUtf8(json.toString() + System.lineSeparator());
    }

    /** 以 UTF-8 编码把文本写入进程标准输出。 */
    private static void writeStdoutUtf8(String text) {
        try {
            PrintStream utf8 = new PrintStream(System.out, true, "UTF-8");
            utf8.print(text);
            utf8.flush();
        } catch (UnsupportedEncodingException e) {
            // UTF-8 是 JVM 必备字符集，正常不会走到这里；退回默认输出保证不丢数据。
            System.out.print(text);
        }
    }

    /** 过滤掉自身 pid 对应的进程；pid 未知（{@code <= 0}）时原样返回。 */
    private static List<ProcessScanner.ProcessInfo> withoutSelf(List<ProcessScanner.ProcessInfo> processes, int selfPid) {
        if (selfPid <= 0 || processes == null || processes.isEmpty()) {
            return processes;
        }
        List<ProcessScanner.ProcessInfo> filtered = new ArrayList<ProcessScanner.ProcessInfo>(processes.size());
        for (ProcessScanner.ProcessInfo info : processes) {
            if (info.pid != selfPid) {
                filtered.add(info);
            }
        }
        return filtered;
    }

    /**
     * 将字符串转义为 JSON 字符串字面量内容（不含外层引号）。
     *
     * @param value 原始文本，允许为 {@code null}
     * @return 去掉引号、反斜杠与控制字符的转义结果；{@code null} 返回空串
     */
    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /**
     * 解析 {@code --pid=<n>}，用于跳过自动发现直接指定目标进程。
     *
     * @return 解析出的值；未指定时 {@link #PID_UNSPECIFIED}，值非法时 {@link #PID_INVALID}
     */
    private static int parsePid(String[] args) {
        if (args == null) {
            return PID_UNSPECIFIED;
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith("--pid=")) {
                try {
                    return Integer.parseInt(arg.substring("--pid=".length()).trim());
                } catch (NumberFormatException e) {
                    return PID_INVALID;
                }
            }
        }
        return PID_UNSPECIFIED;
    }

    /**
     * 定位本类所在的 jar 的绝对路径（它同时就是待注入的 agent jar）。
     *
     * @throws Exception 代码源不可用或路径不是合法文件 URL 时抛出
     */
    private static File selfJar() throws Exception {
        return CodeSources.toFile(Nocturne.class);
    }

    /**
     * 向用户展示一条信息：始终打印到标准输出，并在当前 JVM 没有控制台（例如双击 jar 后由
     * javaw 启动）时额外弹出对话框，避免静默失败。
     *
     * @param message 面向最终用户的提示文本
     */
    private static void notifyUser(String message) {
        System.out.println("[nocturne] " + message);
        if (System.console() != null) {
            return;
        }
        try {
            javax.swing.JOptionPane.showMessageDialog(null, message, "nocturne",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } catch (Throwable ignored) {
            // Headless or no display: stdout is all we have.
        }
    }
}
