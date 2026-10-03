package dev.noturne.core;

import dev.noturne.core.attach.AttachException;
import dev.noturne.core.attach.Attacher;
import dev.noturne.core.attach.ProcessScanner;
import dev.noturne.core.attach.ToolsJarBootstrap;

import java.io.File;
import java.net.URLDecoder;
import java.util.List;

/**
 * Entry point of the loader jar.
 *
 * <p>Double-click / {@code java -jar}: scan for a running Minecraft, pick one, attach,
 * and load this very jar as the agent. When the jar is instead loaded as a Fabric / Forge /
 * NeoForge mod or via {@code -javaagent}, the loader entry points take over instead.
 */
public final class Noturne {

    private Noturne() {
    }

    public static void main(String[] args) throws Exception {
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

    /** {@code --list-json} prints the process list for GUI shells to consume. */
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

    /** Emits {@code [{"pid":…,"title":…,"command":…}]} on stdout. */
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

    /** {@code --pid=<n>} overrides auto-discovery. */
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

    /** Absolute path of the jar this class was loaded from. */
    private static File selfJar() throws Exception {
        String path = Noturne.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        return new File(URLDecoder.decode(path, "UTF-8"));
    }

    /**
     * A double-clicked jar runs under javaw with no console, so stdout is invisible. When there is
     * no console, surface the outcome in a dialog instead of failing silently.
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
