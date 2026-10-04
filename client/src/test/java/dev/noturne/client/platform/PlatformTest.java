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
        // L-60 回归：无法判定的平台不得返回无扩展名裸名，否则加载失败的错误指向不可读的名字
        assertTrue(Platform.UNKNOWN.libraryFileName("noturne").endsWith(".unknown"),
                "unknown platform must produce a diagnosable library name");
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
     * 冒烟验证当前机器解析出的平台与架构是可用的。
     *
     * <p>不断言「非 UNKNOWN」——那会让测试在三大平台之外必然失败（L-16）；这里只要求
     * 结果是合法枚举值、架构串非空，与运行环境无关。
     */
    @Test
    void currentPlatformAndArchitectureReportUsableValues() {
        Platform current = Platform.current();
        assertTrue(current != null, "current() must never return null");
        assertTrue(Platform.architecture().length() > 0, "architecture must be non-empty");
    }
}
