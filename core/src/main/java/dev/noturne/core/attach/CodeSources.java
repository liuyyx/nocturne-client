package dev.noturne.core.attach;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;

/**
 * 把类加载位置的 {@link CodeSource} 解析成文件系统路径。
 *
 * <p>不能使用 {@code URLDecoder.decode(url.getPath(), "UTF-8")}：那个解码器处理的是
 * {@code application/x-www-form-urlencoded} 语义，会把路径里<em>字面量</em>的 {@code +}
 * 变成空格（例如 {@code C:/Users/a+b/noturne.jar}），从而破坏 classpath 与 agent jar 路径。
 * 这里用 {@link URL#toURI()} 做标准百分号解码，{@code +} 原样保留。
 */
public final class CodeSources {

    /** 工具类，禁止实例化。 */
    private CodeSources() {
    }

    /**
     * 解析某个类的加载位置。
     *
     * @param type 目标类，不可为 {@code null}
     * @return 类所在 jar 或 class 目录的文件路径
     * @throws IOException 没有代码源、协议不是 {@code file} 或路径不是合法文件 URL
     */
    public static File toFile(Class<?> type) throws IOException {
        if (type == null) {
            throw new IOException("type must not be null");
        }
        CodeSource source = type.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        if (location == null) {
            throw new IOException("no code source for " + type.getName());
        }
        if (!"file".equalsIgnoreCase(location.getProtocol())) {
            throw new IOException("unsupported code source protocol for " + type.getName() + ": " + location);
        }
        try {
            return new File(location.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("invalid code source location for " + type.getName() + ": " + location, e);
        }
    }
}
