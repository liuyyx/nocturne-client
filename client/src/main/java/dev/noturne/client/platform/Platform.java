package dev.noturne.client.platform;

import java.util.Locale;

/**
 * Runtime platform detection.
 *
 * <p>The client ships one jar for every target, so anything OS-specific (native library naming,
 * path layout, process handling) has to be decided at runtime rather than build time. Detection
 * takes its inputs as parameters so the classification itself is testable.
 */
public enum Platform {

    WINDOWS,
    MACOS,
    LINUX,
    ANDROID,
    UNKNOWN;

    public static Platform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("java.vm.name", ""));
    }

    /** Classifies an OS/VM pair. Android is checked first: it reports a Linux-flavoured os.name. */
    static Platform detect(String osName, String vmName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String vm = vmName == null ? "" : vmName.toLowerCase(Locale.ROOT);
        if (vm.contains("dalvik") || vm.contains("art") || os.contains("android")) {
            return ANDROID;
        }
        // macOS first: "darwin" contains "win", so a naive windows check would swallow it.
        if (os.contains("mac") || os.contains("darwin") || os.contains("osx")) {
            return MACOS;
        }
        if (os.contains("win")) {
            return WINDOWS;
        }
        if (os.contains("linux") || os.contains("nux")) {
            return LINUX;
        }
        return UNKNOWN;
    }

    public boolean isUnix() {
        return this == LINUX || this == MACOS;
    }

    public boolean isDesktop() {
        return this == WINDOWS || this == MACOS || this == LINUX;
    }

    /** Normalised CPU architecture: {@code x86_64}, {@code x86}, {@code aarch64} or the raw value. */
    public static String architecture() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.isEmpty()) {
            return "unknown";
        }
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }
        if (arch.equals("x86_64") || arch.equals("amd64")) {
            return "x86_64";
        }
        if (arch.contains("86")) {
            return "x86";
        }
        return arch;
    }

    /** Platform-correct file name for a native library, e.g. {@code noturne} → {@code noturne.dll}. */
    public String libraryFileName(String base) {
        switch (this) {
            case WINDOWS:
                return base + ".dll";
            case MACOS:
                return "lib" + base + ".dylib";
            case LINUX:
            case ANDROID:
                return "lib" + base + ".so";
            default:
                return base;
        }
    }

    /** The java launcher in a JDK/JRE directory. */
    public String javaExecutableName() {
        return this == WINDOWS ? "java.exe" : "java";
    }
}
