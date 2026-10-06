package dev.nocturne.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.nocturne.core.attach.CurrentProcess;
import dev.nocturne.core.attach.ProcessScanner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link Nocturne} 命令行入口的关键契约测试。
 *
 * <p>覆盖 H-04（自身进程必须从候选中排除）、H-05（{@code --list-json} 只输出 Minecraft 进程，
 * 找不到时输出空数组而非退回全部 JVM）与 M-127（非法 {@code --pid} 必须以用法错误退出）。
 */
class NocturneTest {

    /** M-127：{@code --pid} 解析必须区分「未指定」与「值非法」，绝不能把非法值当成自动挑选 */
    @Test
    void pidParsingRejectsInvalidValues() throws Exception {
        assertEquals(-1, parsePid(new String[]{}), "no --pid means unspecified");
        assertEquals(-1, parsePid(new String[]{"--list-json"}));
        assertEquals(-2, parsePid(new String[]{"--pid=abc"}), "non-numeric must be rejected");
        assertEquals(-2, parsePid(new String[]{"--pid="}), "empty value must be rejected");
        assertEquals(-2, parsePid(new String[]{"--pid=  "}), "blank value must be rejected");
        assertEquals(42, parsePid(new String[]{"--pid=42"}));
        assertEquals(0, parsePid(new String[]{"--pid=0"}), "0 parses but is rejected later as non-positive");
    }

    /** H-04：自身 pid 必须从候选列表中剔除，其余进程保持原顺序 */
    @Test
    void selfProcessIsExcludedFromCandidates() throws Exception {
        Assumptions.assumeTrue(CurrentProcess.pid() > 0, "cannot determine own pid");
        int self = CurrentProcess.pid();

        List<ProcessScanner.ProcessInfo> input = new ArrayList<ProcessScanner.ProcessInfo>();
        input.add(processInfo(self, "javaw.exe", "minecraft", "self"));
        input.add(processInfo(9999, "javaw.exe", "minecraft", "other"));

        List<ProcessScanner.ProcessInfo> filtered = withoutSelf(input, self);
        assertEquals(1, filtered.size(), "the injector must never target its own JVM");
        assertEquals(9999, filtered.get(0).pid);
    }

    /**
     * H-05：{@code --list-json} 的输出必须恰好对应「Minecraft 进程减去自身」，
     * 一个找不到时输出 {@code []}，绝不能退回列出全部 JVM。
     */
    @Test
    void listJsonContainsOnlyMinecraftProcessesMinusSelf() throws Exception {
        Assumptions.assumeTrue(CurrentProcess.pid() > 0, "cannot determine own pid");

        List<ProcessScanner.ProcessInfo> minecraft =
                withoutSelf(ProcessScanner.minecraftProcesses(), CurrentProcess.pid());

        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(capture, true, "UTF-8"));
        try {
            printProcessesAsJson();
        } finally {
            System.setOut(saved);
        }

        String json = new String(capture.toByteArray(), Charset.forName("UTF-8")).trim();
        assertTrue(json.startsWith("[") && json.endsWith("]"), "output must be a JSON array: " + json);
        JsonArray parsed = JsonParser.parseString(json).getAsJsonArray();
        assertEquals(minecraft.size(), parsed.size(),
                "list must mirror the minecraft processes only, never fall back to all JVMs");

        for (int i = 0; i < parsed.size(); i++) {
            assertTrue(parsed.get(i).getAsJsonObject().has("pid"));
            assertTrue(parsed.get(i).getAsJsonObject().has("title"));
            assertTrue(parsed.get(i).getAsJsonObject().has("command"));
            assertTrue(parsed.get(i).getAsJsonObject().get("pid").getAsInt() != CurrentProcess.pid(),
                    "the injector's own pid must not appear");
        }
    }

    /**
     * M-127 端到端：非法 {@code --pid} 必须打印用法错误并以退出码 2 结束（而非静默自动挑选目标）。
     */
    @Test
    void invalidPidExitsWithUsageCode() throws Exception {
        File java = javaExecutable();
        Assumptions.assumeTrue(java.isFile(), "java launcher not found: " + java);

        ProcessBuilder builder = new ProcessBuilder(
                java.getAbsolutePath(),
                "-cp", System.getProperty("java.class.path"),
                "dev.nocturne.core.Nocturne",
                "--pid=not-a-number");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean done = process.waitFor(60, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            fail("subprocess did not exit in time");
        }
        String output = readFully(process);
        assertEquals(2, process.exitValue(), "usage errors must exit with code 2: " + output);
        assertTrue(output.contains("invalid --pid"), "the error must name the offending option: " + output);
    }

    // ------------------------------------------------------------- 测试辅助（最小反射宿主）

    /** 调用私有的 {@code parsePid(String[])} */
    private static int parsePid(String[] args) throws Exception {
        Method method = Nocturne.class.getDeclaredMethod("parsePid", String[].class);
        method.setAccessible(true);
        return ((Number) method.invoke(null, (Object) args)).intValue();
    }

    /** 调用私有的 {@code withoutSelf(List, int)} */
    @SuppressWarnings("unchecked")
    private static List<ProcessScanner.ProcessInfo> withoutSelf(
            List<ProcessScanner.ProcessInfo> processes, int selfPid) throws Exception {
        Method method = Nocturne.class.getDeclaredMethod("withoutSelf", List.class, int.class);
        method.setAccessible(true);
        return (List<ProcessScanner.ProcessInfo>) method.invoke(null, processes, selfPid);
    }

    /** 调用私有的 {@code printProcessesAsJson()} */
    private static void printProcessesAsJson() throws Exception {
        Method method = Nocturne.class.getDeclaredMethod("printProcessesAsJson");
        method.setAccessible(true);
        method.invoke(null);
    }

    /** 通过包私有构造器造一个 {@link ProcessScanner.ProcessInfo} 替身 */
    private static ProcessScanner.ProcessInfo processInfo(int pid, String image, String command, String title)
            throws Exception {
        Constructor<ProcessScanner.ProcessInfo> ctor = ProcessScanner.ProcessInfo.class
                .getDeclaredConstructor(int.class, String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(pid, image, command, title);
    }

    /** 定位当前 JVM 的 java 启动器 */
    private static File javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return new File(new File(System.getProperty("java.home"), "bin"), windows ? "java.exe" : "java");
    }

    /** 读完子进程的全部输出 */
    private static String readFully(Process process) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = process.getInputStream().read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), Charset.forName("UTF-8"));
    }
}
