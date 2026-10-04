package dev.noturne.client.platform;

import java.util.Locale;

/**
 * 运行时平台判定。
 *
 * <p>客户端只发布一个 jar，需要适配所有目标平台，因此凡是与操作系统相关的事项（本地库命名、
 * 路径布局、进程处理）都必须在运行时而非构建期决定。判定函数把输入作为参数传入，
 * 使得分类逻辑本身可被单元测试。
 */
public enum Platform {

    /** 微软 Windows。 */
    WINDOWS,
    /** Apple macOS。 */
    MACOS,
    /** Linux 桌面发行版。 */
    LINUX,
    /** Android，通过 Dalvik/ART 或 os.name 判定。 */
    ANDROID,
    /** 无法识别的平台，各平台特有行为一律回退到中性实现。 */
    UNKNOWN;

    /** @return 当前 JVM 所在平台的判定结果 */
    public static Platform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("java.vm.name", ""));
    }

    /**
     * 对 os.name / java.vm.name 组合做分类。
     *
     * <p>先判 Android：它报告的 os.name 带有 Linux 特征，若顺序颠倒会被误判为 Linux。
     *
     * @return 分类结果；无任何匹配特征时返回 {@link #UNKNOWN}
     */
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

    /** @return 是否为类 Unix 平台（Linux 或 macOS） */
    public boolean isUnix() {
        return this == LINUX || this == MACOS;
    }

    /** @return 是否为桌面平台（Windows、macOS 或 Linux，不含 Android） */
    public boolean isDesktop() {
        return this == WINDOWS || this == MACOS || this == LINUX;
    }

    /** 规范化的 CPU 架构名：{@code x86_64}、{@code x86}、{@code aarch64}，无法识别时返回原值。 */
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

    /** 生成符合本平台约定的本地库文件名，例如 {@code noturne} → {@code noturne.dll}。 */
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

    /** @return JDK/JRE 目录下的 java 启动文件名（Windows 为 {@code java.exe}，其余为 {@code java}） */
    public String javaExecutableName() {
        return this == WINDOWS ? "java.exe" : "java";
    }
}
