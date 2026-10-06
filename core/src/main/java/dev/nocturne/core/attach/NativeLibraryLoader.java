package dev.nocturne.core.attach;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * 原生库加载器：从 jar 资源解压平台对应的库到临时目录后 {@code System.load}。
 *
 * <p>资源布局：{@code /native/<os>-<arch>/<lib名>.<后缀>}，例如
 * {@code /native/windows-x64/nocturne-attach.dll}、{@code /native/linux-x64/nocturne-attach.so}、
 * {@code /native/macos-aarch64/nocturne-attach.dylib}。未产出该平台原生库时返回
 * {@code false}（调用方按“本平台无原生策略”处理），绝不抛异常——抛异常是
 * {@code ensureLoaded} 在确认不可用后统一做的事。
 */
public final class NativeLibraryLoader {

    /** 工具类，禁止实例化。 */
    private NativeLibraryLoader() {
    }

    /**
     * 加载指定原生库。
     *
     * @param baseName 库基名（不带前缀后缀，如 {@code nocturne-attach}）
     * @return 加载成功返回 {@code true}，否则 {@code false}
     */
    public static boolean load(String baseName) {
        Platform platform = platform();
        if (platform == null) {
            return false;
        }
        String resource = "/native/" + platform.directory + "/" + baseName + platform.suffix;
        InputStream in = NativeLibraryLoader.class.getResourceAsStream(resource);
        if (in == null) {
            return false;
        }
        try {
            File temp = File.createTempFile(baseName + "-", platform.suffix);
            temp.deleteOnExit();
            java.io.OutputStream out = new java.io.FileOutputStream(temp);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响已写入的内容。
                }
            }
            System.load(temp.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 关闭失败不影响加载结果。
            }
        }
    }

    /**
     * 按当前平台解析资源目录与库后缀。
     *
     * @return 平台描述；该平台没有原生库时返回 {@code null}
     */
    static Platform platform() {
        return platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * 按给定 {@code os.name} / {@code os.arch} 解析资源目录与库后缀（便于测试各平台组合）。
     *
     * <p>刻意不用「arch 含 64」这种粗糙判断：Windows-on-ARM 也含 "64"，但远线程桩是 x64
     * 机器码，在 ARM64 目标上不可用，必须返回 {@code null} 让调用方回落 JDK attach API。
     *
     * @param osName 形如 {@code Windows 11} / {@code Linux} / {@code Mac OS X}
     * @param osArch 形如 {@code amd64} / {@code aarch64}
     * @return 平台描述；该平台没有原生库时返回 {@code null}
     */
    static Platform platform(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        String archName;
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            archName = "aarch64";
        } else if (arch.contains("64")) {
            archName = "x64";
        } else {
            return null; // 32 位不产出原生库
        }
        if (os.contains("win")) {
            return "x64".equals(archName) ? new Platform("windows-x64", ".dll") : null;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new Platform("macos-" + archName, ".dylib");
        }
        if (os.contains("linux")) {
            return new Platform("linux-" + archName, ".so");
        }
        return null;
    }

    /** 目标平台的原生库位置：资源目录（{@code <os>-<arch>}）与文件后缀。 */
    static final class Platform {

        /** 资源目录名，如 {@code windows-x64}。 */
        final String directory;

        /** 库文件后缀，如 {@code .dll}。 */
        final String suffix;

        Platform(String directory, String suffix) {
            this.directory = directory;
            this.suffix = suffix;
        }
    }
}
