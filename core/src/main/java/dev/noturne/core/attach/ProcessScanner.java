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
 * Finds candidate Minecraft JVM processes.
 *
 * <p>Deliberately implemented on top of OS tooling (PowerShell / ps) instead of Java 9's
 * {@code ProcessHandle}, because the whole module must stay loadable on a Java 8 JVM
 * (Minecraft 1.8.9 is a supported target).
 */
public final class ProcessScanner {

    public static final class ProcessInfo {
        public final int pid;
        public final String image;
        public final String commandLine;
        /** Top-level window title, when the process has one (usually the game window). */
        public final String windowTitle;

        ProcessInfo(int pid, String image, String commandLine, String windowTitle) {
            this.pid = pid;
            this.image = image;
            this.commandLine = commandLine;
            this.windowTitle = windowTitle == null ? "" : windowTitle;
        }

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

        /** Best label for the UI: the window title when present, else the command line. */
        public String display() {
            if (!windowTitle.isEmpty()) {
                return windowTitle + "      \u2014      " + abbreviate(commandLine, 90);
            }
            return abbreviate(commandLine, 150);
        }

        @Override
        public String toString() {
            return pid + "  " + image + "  " + (windowTitle.isEmpty() ? "" : "[" + windowTitle + "] ") + abbreviate(commandLine, 120);
        }
    }

    private ProcessScanner() {
    }

    /** All live JVM processes (java / javaw / javaw.exe). */
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

    /** JVM processes that look like Minecraft. */
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

    private static List<ProcessInfo> onWindows() throws IOException, InterruptedException {
        // tasklist is a native tool: it starts in milliseconds and `/V` already includes the window
        // title column. PowerShell was measured at 10s–2min on this machine, which is unusable for
        // something the UI calls on every refresh.
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

    private static List<String> run(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        List<String> lines = new ArrayList<String>();
        InputStream in = process.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
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

    /** Minimal RFC-4180 CSV field splitter (handles quoted fields, doubled quotes, CRLF). */
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

    private static String abbreviate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }
}
