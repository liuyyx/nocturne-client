package dev.nocturne.core.attach;

import org.junit.jupiter.api.Test;

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
    void errnoIsRenderedWithName() {
        assertEquals("EACCES(13)", PosixAttachNative.describe(13));
        assertEquals("ENOENT(2)", PosixAttachNative.describe(2));
        assertEquals("errno(9999)", PosixAttachNative.describe(9999));
    }
}
