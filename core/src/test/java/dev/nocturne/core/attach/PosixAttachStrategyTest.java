package dev.nocturne.core.attach;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P7-2 的纯逻辑测试：套接字路径、平台映射、errno 文本。
 *
 * <p>套接字连接本身无法在 Windows 上执行（本机既没有 Linux JVM，也编译不出 `.so`），
 * 因此这里只覆盖能在任何平台确定判定的部分；端到端验证待 Linux/macOS 实机。
 */
class PosixAttachStrategyTest {

    @Test
    void socketPathUsesTmpDirAndPid() {
        assertEquals("/tmp/.java_pid1234", PosixAttachStrategy.socketPath("/tmp", 1234));
    }

    @Test
    void socketPathStripsTrailingSeparators() {
        assertEquals("/var/tmp/.java_pid7", PosixAttachStrategy.socketPath("/var/tmp///", 7));
    }

    @Test
    void socketPathFallsBackToTmpWhenUnset() {
        assertEquals("/tmp/.java_pid42", PosixAttachStrategy.socketPath(null, 42));
        assertEquals("/tmp/.java_pid42", PosixAttachStrategy.socketPath("", 42));
    }

    @Test
    void platformMapsUnixTargets() {
        NativeLibraryLoader.Platform linux =
                NativeLibraryLoader.platform("Linux", "amd64");
        assertNotNull(linux);
        assertEquals("linux-x64", linux.directory);
        assertEquals(".so", linux.suffix);

        NativeLibraryLoader.Platform macArm =
                NativeLibraryLoader.platform("Mac OS X", "aarch64");
        assertNotNull(macArm);
        assertEquals("macos-aarch64", macArm.directory);
        assertEquals(".dylib", macArm.suffix);

        NativeLibraryLoader.Platform win =
                NativeLibraryLoader.platform("Windows 11", "amd64");
        assertNotNull(win);
        assertEquals("windows-x64", win.directory);
        assertEquals(".dll", win.suffix);
    }

    @Test
    void platformRejectsUnsupportedCombinations() {
        // Windows on ARM 不含 x64 原生库：远线程桩是 x64 机器码，必须回落 JDK attach API。
        assertNull(NativeLibraryLoader.platform("Windows 11", "aarch64"));
        // 32 位与未知系统同样没有产物。
        assertNull(NativeLibraryLoader.platform("Linux", "x86"));
        assertNull(NativeLibraryLoader.platform("SunOS", "amd64"));
    }

    @Test
    void failedWriteDoesNotReplayThroughFallbackTmp() throws Exception {
        Path directory = Files.createTempDirectory("nocturne-posix-write-test");
        Path fakeClasses = directory.resolve("classes");
        compileFakeNative(directory, fakeClasses);
        URL core = PosixAttachStrategy.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI().toURL();
        URL[] classpath = {fakeClasses.toUri().toURL(), core};
        try (URLClassLoader loader = new URLClassLoader(classpath,
                PosixAttachStrategyTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("dev.nocturne.")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            loaded = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> type = Class.forName(PosixAttachStrategy.class.getName(), true, loader);
            Object strategy = type.getConstructor().newInstance();
            java.io.File agent = directory.resolve("agent.jar").toFile();
            assertTrue(agent.createNewFile());
            java.lang.reflect.InvocationTargetException failure = assertThrows(
                    java.lang.reflect.InvocationTargetException.class,
                    () -> type.getMethod("attach", int.class, java.io.File.class, String.class)
                            .invoke(strategy, 1234, agent, null));
            assertEquals("dev.nocturne.core.attach.DeliveryUnknownException",
                    failure.getCause().getClass().getName());
            Class<?> nativeType = Class.forName(PosixAttachNative.class.getName(), true, loader);
            java.util.List<?> calls = (java.util.List<?>) nativeType.getField("calls").get(null);
            assertEquals(1, calls.size(), "写入失败后不得再连接 /tmp 重放");
            assertTrue(String.valueOf(calls.get(0)).endsWith(".java_pid1234"));
        }
    }

    private static void compileFakeNative(Path directory, Path output) throws Exception {
        Files.createDirectories(output);
        Path source = directory.resolve("PosixAttachNative.java");
        String text = "package dev.nocturne.core.attach;\n"
                + "public final class PosixAttachNative {\n"
                + " public static final java.util.List<String> calls = new java.util.ArrayList<String>();\n"
                + " public static int connect(String path) { calls.add(path); return 1; }\n"
                + " public static void write(int fd, byte[] request) throws java.io.IOException {"
                + " throw new java.io.IOException(\"partial write\"); }\n"
                + " public static int read(int fd, byte[] b, int o, int l) { return 0; }\n"
                + " public static void close(int fd) { }\n"
                + "}\n";
        Files.write(source, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        int result = compiler.run(null, null, null, "-d", output.toString(), source.toString());
        assertEquals(0, result, "测试 native 替身必须编译成功");
    }

    @Test
    void errnoIsRenderedWithName() {
        assertEquals("EACCES(13)", PosixAttachNative.describe(13));
        assertEquals("ENOENT(2)", PosixAttachNative.describe(2));
        assertEquals("errno(9999)", PosixAttachNative.describe(9999));
    }
}
