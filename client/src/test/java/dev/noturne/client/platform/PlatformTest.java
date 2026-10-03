package dev.noturne.client.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformTest {

    @Test
    void classifiesCommonOperatingSystems() {
        assertEquals(Platform.WINDOWS, Platform.detect("Windows 11", "OpenJDK 64-Bit Server VM"));
        assertEquals(Platform.MACOS, Platform.detect("Mac OS X", "OpenJDK 64-Bit Server VM"));
        assertEquals(Platform.MACOS, Platform.detect("darwin", "OpenJDK 64-Bit Server VM"));
        assertEquals(Platform.LINUX, Platform.detect("Linux", "OpenJDK 64-Bit Server VM"));
    }

    @Test
    void androidWinsOverLinuxFlavouredOsName() {
        assertEquals(Platform.ANDROID, Platform.detect("Linux", "Dalvik"));
        assertEquals(Platform.ANDROID, Platform.detect("Linux", "ART"));
        assertEquals(Platform.ANDROID, Platform.detect("Android 14", "OpenJDK"));
    }

    @Test
    void unknownInputsAreReportedAsUnknown() {
        assertEquals(Platform.UNKNOWN, Platform.detect("", ""));
        assertEquals(Platform.UNKNOWN, Platform.detect(null, null));
        assertEquals(Platform.UNKNOWN, Platform.detect("Plan9", "Some VM"));
    }

    @Test
    void libraryNamesFollowPlatformConventions() {
        assertEquals("noturne.dll", Platform.WINDOWS.libraryFileName("noturne"));
        assertEquals("libnoturne.dylib", Platform.MACOS.libraryFileName("noturne"));
        assertEquals("libnoturne.so", Platform.LINUX.libraryFileName("noturne"));
        assertEquals("java.exe", Platform.WINDOWS.javaExecutableName());
        assertEquals("java", Platform.LINUX.javaExecutableName());
    }

    @Test
    void unixAndDesktopFlags() {
        assertTrue(Platform.LINUX.isUnix());
        assertTrue(Platform.MACOS.isUnix());
        assertFalse(Platform.WINDOWS.isUnix());
        assertTrue(Platform.WINDOWS.isDesktop());
        assertFalse(Platform.ANDROID.isDesktop());
    }

    @Test
    void currentPlatformIsResolvedOnThisMachine() {
        assertNotEquals(Platform.UNKNOWN, Platform.current());
        assertTrue(Platform.architecture().length() > 0);
    }
}
