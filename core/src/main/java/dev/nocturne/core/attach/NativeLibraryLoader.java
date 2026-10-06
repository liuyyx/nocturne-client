package dev.nocturne.core.attach;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * 原生库加载器：从 jar 资源解压平台对应的库到临时目录后 {@code System.load}。
 *
 * <p>资源布局：{@code /native/<os>-<arch>/<lib名>}，例如
 * {@code /native/windows-x64/nocturne-attach.dll}。非 Windows 或资源缺失时返回
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
        String resource = resourcePath(baseName);
        if (resource == null) {
            return false;
        }
        InputStream in = NativeLibraryLoader.class.getResourceAsStream(resource);
        if (in == null) {
            return false;
        }
        try {
            File temp = File.createTempFile(baseName + "-", librarySuffix());
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
     * 按当前平台拼资源路径；不支持的平台返回 {@code null}。
     *
     * @param baseName 库基名
     * @return 资源路径或 {@code null}
     */
    static String resourcePath(String baseName) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        boolean windows = os.contains("win");
        boolean x64 = arch.contains("64");
        if (windows && x64) {
            return "/native/windows-x64/" + baseName + ".dll";
        }
        return null;
    }

    /** 当前平台的库文件后缀（仅 Windows 有产物，其他平台走不到这里）。 */
    private static String librarySuffix() {
        return ".dll";
    }
}
