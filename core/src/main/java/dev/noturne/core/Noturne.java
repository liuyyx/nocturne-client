package dev.noturne.core;

import dev.noturne.core.attach.AttachException;
import dev.noturne.core.attach.Attacher;
import dev.noturne.core.attach.ProcessScanner;
import dev.noturne.core.attach.ToolsJarBootstrap;

import java.io.File;
import java.net.URLDecoder;
import java.util.List;

/**
 * loader jar 的命令行入口。
 *
 * <p>双击运行 / {@code java -jar} 时：扫描正在运行的 Minecraft 进程，选择一个进行 attach，
 * 并把本 jar 作为 agent 注入。当本 jar 改为以 Fabric / Forge / NeoForge 模组形式加载，
 * 或通过 {@code -javaagent} 加载时，则由相应的 loader 入口接管，不进入此处的流程。
 */
public final class Noturne {

    /** 工具类，禁止实例化。 */
    private Noturne() {
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
        if (ToolsJarBootstrap.relaunchIfNeeded("dev.noturne.core.Noturne", args)) {
            return;
        }
        int pid = parsePid(args);

        if (hasFlag(args, "--list-json")) {
            printProcessesAsJson();
            return;
        }
        File self = selfJar();

        List<ProcessScanner.ProcessInfo> candidates = ProcessScanner.minecraftProcesses();
        if (pid < 0) {
            if (candidates.isEmpty()) {
                StringBuilder message = new StringBuilder("No Minecraft process found.\n\nLive JVMs:\n");
                List<ProcessScanner.ProcessInfo> all = ProcessScanner.javaProcesses();
                if (all.isEmpty()) {
                    message.append("    (none)\n");
                } else {
                    for (ProcessScanner.ProcessInfo p : all) {
                        message.append("    ").append(p).append('\n');
                    }
                }
                message.append("\nStart Minecraft first, then run this jar again.");
                notifyUser(message.toString());
                return;
            }
            ProcessScanner.ProcessInfo chosen = candidates.get(0);
            pid = chosen.pid;
            System.out.println("[noturne] target: " + chosen);
        }

        System.out.println("[noturne] attaching to pid " + pid + " with " + self.getName());
        try {
            Attacher.attach(pid, self, "");
        } catch (AttachException e) {
            System.err.println("[noturne] attach failed: " + e.getMessage());
            // 针对最常见的失败（attach API 不在 classpath 上）给出可操作的提示。
            Throwable cause = e.getCause();
            if (cause instanceof ClassNotFoundException
                    && String.valueOf(cause.getMessage()).contains("com.sun.tools.attach")) {
                System.err.println("[noturne] the JDK attach API is not on this JVM's classpath.");
                System.err.println("[noturne]   - JDK 8: run with tools.jar on the classpath (JDK, not JRE),");
                System.err.println("[noturne]   - JDK 9+: the jdk.attach module must be present,");
                System.err.println("[noturne]   - or use the native attach strategy (Phase 7).");
            } else if (cause != null) {
                System.err.println("[noturne] cause: " + cause);
            }
            System.exit(1);
        }
        System.out.println("[noturne] agent loaded");
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
        List<ProcessScanner.ProcessInfo> processes = ProcessScanner.minecraftProcesses();
        if (processes.isEmpty()) {
            processes = ProcessScanner.javaProcesses();
        }
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
        System.out.println(json);
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

    /** 解析 {@code --pid=<n>}，用于跳过自动发现直接指定目标进程。 */
    private static int parsePid(String[] args) {
        if (args == null) {
            return -1;
        }
        for (String arg : args) {
            if (arg.startsWith("--pid=")) {
                try {
                    return Integer.parseInt(arg.substring("--pid=".length()).trim());
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /**
     * 定位本类所在的 jar 的绝对路径（它同时就是待注入的 agent jar）。
     *
     * @throws Exception 代码源不可用或路径含非 UTF-8 字节时抛出
     */
    private static File selfJar() throws Exception {
        String path = Noturne.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        return new File(URLDecoder.decode(path, "UTF-8"));
    }

    /**
     * 向用户展示一条信息：始终打印到标准输出，并在当前 JVM 没有控制台（例如双击 jar 后由
     * javaw 启动）时额外弹出对话框，避免静默失败。
     *
     * @param message 面向最终用户的提示文本
     */
    private static void notifyUser(String message) {
        System.out.println("[noturne] " + message);
        if (System.console() != null) {
            return;
        }
        try {
            javax.swing.JOptionPane.showMessageDialog(null, message, "noturne",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } catch (Throwable ignored) {
            // Headless or no display: stdout is all we have.
        }
    }
}
