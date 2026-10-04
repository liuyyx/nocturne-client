package dev.noturne.client.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台识别的单元测试：验证 {@link Platform#detect} 对常见 os.name/vmName 组合的分类优先级
 * （Android 优先于 Linux）、未知输入的兜底，以及各平台命名约定与能力标志、当前机器解析。
 */
class PlatformTest {

    /** 验证 Windows/macOS（含 darwin 别名）/Linux 的 os.name 能映射到对应枚举。 */
    @Test
    void classifiesCommonOperatingSystems() {
        assertEquals(Platform.WINDOWS, Platform.detect("Windows 11", "OpenJDK 64-Bit Server VM"));
        assertEquals(Platform.MACOS, Platform.detect("Mac OS X", "OpenJDK 64-Bit Server VM"));
        assertEquals(Platform.MACOS, Platform.detect("darwin", "OpenJDK 64-Bit Server VM"));
        assertEquals(Platform.LINUX, Platform.detect("Linux", "OpenJDK 64-Bit Server VM"));
    }

    /**
     * 验证 Android 判定优先于 os.name 的 Linux 表象：只要 vmName 为 Dalvik/ART，即便 os.name
     * 显示 Linux 也归类为 Android，避免在安卓上误走桌面 Linux 路径。
     */
    @Test
    void androidWinsOverLinuxFlavouredOsName() {
        assertEquals(Platform.ANDROID, Platform.detect("Linux", "Dalvik"));
        assertEquals(Platform.ANDROID, Platform.detect("Linux", "ART"));
        assertEquals(Platform.ANDROID, Platform.detect("Android 14", "OpenJDK"));
    }

    /** 验证空值、空串与无法识别的 os.name 一律归为 {@link Platform#UNKNOWN}，不抛异常。 */
    @Test
    void unknownInputsAreReportedAsUnknown() {
        assertEquals(Platform.UNKNOWN, Platform.detect("", ""));
        assertEquals(Platform.UNKNOWN, Platform.detect(null, null));
        assertEquals(Platform.UNKNOWN, Platform.detect("Plan9", "Some VM"));
    }

    /** 验证原生库与 java 可执行文件在各平台的下述命名约定（前缀/后缀）。 */
    @Test
    void libraryNamesFollowPlatformConventions() {
        assertEquals("noturne.dll", Platform.WINDOWS.libraryFileName("noturne"));
        assertEquals("libnoturne.dylib", Platform.MACOS.libraryFileName("noturne"));
        assertEquals("libnoturne.so", Platform.LINUX.libraryFileName("noturne"));
        assertEquals("java.exe", Platform.WINDOWS.javaExecutableName());
        assertEquals("java", Platform.LINUX.javaExecutableName());
    }

    /** 验证 Unix 与桌面标志：Linux/macOS 为 Unix，Windows 为桌面但非 Unix，Android 非桌面。 */
    @Test
    void unixAndDesktopFlags() {
        assertTrue(Platform.LINUX.isUnix());
        assertTrue(Platform.MACOS.isUnix());
        assertFalse(Platform.WINDOWS.isUnix());
        assertTrue(Platform.WINDOWS.isDesktop());
        assertFalse(Platform.ANDROID.isDesktop());
    }

    /**
     * 冒烟验证当前运行机器能解析出确定平台（非 UNKNOWN）并给出非空架构串，
     * 确保 Platform 的静态初始化在本环境可用。
     */
    @Test
    void currentPlatformIsResolvedOnThisMachine() {
        assertNotEquals(Platform.UNKNOWN, Platform.current());
        assertTrue(Platform.architecture().length() > 0);
    }
}
