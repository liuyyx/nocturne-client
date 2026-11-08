package dev.nocturne.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bootstrap 桥的 fail-closed 与版本闸门。
 *
 * <p>两条回归：
 * <ul>
 *   <li>桥装不上（临时目录不可写）时旧实现只是打一行日志继续跑，随后注册的转换器把必然
 *       NoClassDefFoundError 的调用织进游戏——必须返回 false 让调用方停手；</li>
 *   <li>bootstrap 层 first-wins：同进程已有旧 jar 注入过时，新类根本不会被定义。桥 jar 必须
 *       自带版本资源，安装前与本次构建比对，不一致就拒绝。</li>
 * </ul>
 */
class BootstrapBridgeTest {

    /** 只记录 bootstrap 追加动作的 Instrumentation 替身（不真的改搜索路径）。 */
    private static final class Recorder implements InvocationHandler {
        final List<JarFile> bootstrapSearches = new ArrayList<JarFile>();

        Instrumentation instrumentation() {
            return (Instrumentation) Proxy.newProxyInstance(
                    Instrumentation.class.getClassLoader(),
                    new Class<?>[]{Instrumentation.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("appendToBootstrapClassLoaderSearch".equals(method.getName())
                    && args != null && args.length == 1 && args[0] instanceof JarFile) {
                bootstrapSearches.add((JarFile) args[0]);
            }
            return null;
        }
    }

    @AfterEach
    void reset() {
        NocturneAgent.resetBootstrapBridgeForTests();
    }

    /** 不可写的目标路径必须 fail-closed：返回 false，且不追加任何东西到 bootstrap。 */
    @Test
    void failsClosedWhenTheBridgeJarCannotBeWritten() throws Exception {
        Path missingParent = Files.createTempDirectory("nocturne-bridge-test")
                .resolve("missing-dir").resolve("bridge.jar");
        Recorder recorder = new Recorder();

        boolean installed = NocturneAgent.writeBridgeJar(
                recorder.instrumentation(), missingParent, new String[0][], "test-version");

        assertFalse(installed, "an unwritable temp path must not report success");
        assertTrue(recorder.bootstrapSearches.isEmpty(),
                "nothing may be appended to the bootstrap search path on failure");
    }

    /** 只读目录里的目标文件同样 fail-closed（1.8.9 现场常见：临时目录被安全软件锁住）。 */
    @Test
    void failsClosedOnAReadOnlyTarget() throws Exception {
        Path dir = Files.createTempDirectory("nocturne-bridge-ro");
        Path target = dir.resolve("bridge.jar");
        Files.write(target, new byte[]{0});
        assertTrue(target.toFile().setReadOnly(), "precondition: file must become read-only");
        Recorder recorder = new Recorder();
        try {
            assertFalse(NocturneAgent.writeBridgeJar(
                            recorder.instrumentation(), target, new String[0][], "test-version"),
                    "a read-only jar target must not report success");
            assertTrue(recorder.bootstrapSearches.isEmpty());
        } finally {
            target.toFile().setWritable(true);
            Files.deleteIfExists(target);
            Files.deleteIfExists(dir);
        }
    }

    /** 桥 jar 必须内嵌版本资源，下一次注入才能判断 bootstrap 里已生效的是哪个构建。 */
    @Test
    void embedsTheBuildVersion() throws Exception {
        Path jar = Files.createTempFile("nocturne-bridge-test", ".jar");
        Recorder recorder = new Recorder();
        try {
            assertTrue(NocturneAgent.writeBridgeJar(
                    recorder.instrumentation(), jar, new String[0][], NocturneAgent.bridgeVersion()));
            assertEquals(1, recorder.bootstrapSearches.size(),
                    "the bridge jar must be appended exactly once");

            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(NocturneAgent.BRIDGE_VERSION_ENTRY);
                assertNotNull(entry, "bridge jar must carry a version resource");
                try (InputStream in = zip.getInputStream(entry)) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[64];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    assertEquals(NocturneAgent.bridgeVersion(),
                            new String(out.toByteArray(), StandardCharsets.UTF_8).trim());
                }
            }
        } finally {
            for (JarFile opened : recorder.bootstrapSearches) {
                opened.close();
            }
            Files.deleteIfExists(jar);
        }
        assertFalse(NocturneAgent.bridgeVersion().trim().isEmpty());
    }

    /** 版本闸门：只有「从未装过」或「版本完全相同」才允许安装。 */
    @Test
    void refusesMismatchedVersions() {
        assertTrue(NocturneAgent.bridgeVersionMatches(null, "1.0"));
        assertTrue(NocturneAgent.bridgeVersionMatches("1.0", "1.0"));
        assertFalse(NocturneAgent.bridgeVersionMatches("0.9", "1.0"));
        // 老构建没有版本资源（空串）同样视为不一致：那正是「旧 jar 已在 bootstrap 里」的形态。
        assertFalse(NocturneAgent.bridgeVersionMatches("", "1.0"));
        assertNotEquals(NocturneAgent.bridgeVersionMatches("0.9", "1.0"),
                NocturneAgent.bridgeVersionMatches("1.0", "1.0"));
    }
}
