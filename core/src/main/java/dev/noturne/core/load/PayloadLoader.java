package dev.noturne.core.load;

import dev.noturne.core.pack.PayloadPack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从任意来源（classpath 资源、jar 条目、任意流）读取加密 payload，解密后转成类加载器。
 *
 * <p>解密后的结果统一是 {@code 类全名 -> 字节码} 的映射，交给
 * {@link MemoryClassLoader} 加载，payload 全程不落盘。
 */
public final class PayloadLoader {

    /** 工具类，禁止实例化。 */
    private PayloadLoader() {
    }

    /**
     * 从流中解密 payload。
     *
     * @param in   输入流，会被完全读取但不会被关闭（所有权仍属调用方）
     * @param key  AES-256 密钥，长度必须为 32 字节
     * @return 类全名到字节码的映射
     * @throws IOException 读取失败或 payload 格式/校验不合法
     */
    public static Map<String, byte[]> read(InputStream in, byte[] key) throws IOException {
        return PayloadPack.unpack(readFully(in), key);
    }

    /**
     * 解密以 classpath 资源形式随 jar 分发的 payload。
     *
     * @param resource 绝对资源路径，例如 {@code /assets/payload.bin}
     * @param key AES-256 密钥
     * @return 类全名到字节码的映射
     * @throws IOException 资源不存在，或解密失败
     */
    public static Map<String, byte[]> readResource(String resource, byte[] key) throws IOException {
        InputStream in = PayloadLoader.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("payload resource not found: " + resource);
        }
        try {
            return read(in, key);
        } finally {
            in.close();
        }
    }

    /**
     * 解密以 jar 内条目形式分发的 payload（例如 loader 自身的 jar）。
     *
     * @param jar    承载 payload 的 jar 文件
     * @param entry  jar 内条目名
     * @param key    AES-256 密钥
     * @return 类全名到字节码的映射
     * @throws IOException 条目不存在，或读取/解密失败
     */
    public static Map<String, byte[]> readJarEntry(File jar, String entry, byte[] key) throws IOException {
        ZipFile zip = new ZipFile(jar);
        try {
            ZipEntry zipEntry = zip.getEntry(entry);
            if (zipEntry == null) {
                throw new IOException("payload entry not found: " + entry + " in " + jar);
            }
            InputStream in = zip.getInputStream(zipEntry);
            try {
                return read(in, key);
            } finally {
                in.close();
            }
        } finally {
            zip.close();
        }
    }

    /**
     * 从普通文件解密 payload（供工具与测试使用）。
     *
     * @param file payload 文件
     * @param key  AES-256 密钥
     * @return 类全名到字节码的映射
     * @throws IOException 读取或解密失败
     */
    public static Map<String, byte[]> readFile(File file, byte[] key) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            return read(in, key);
        } finally {
            in.close();
        }
    }

    /**
     * 用给定字节码表创建一个内存类加载器。
     *
     * @param classes 类全名到字节码的映射
     * @param parent  父加载器；表中没有的类会委派给它
     * @return 新的内存类加载器
     */
    public static MemoryClassLoader newLoader(Map<String, byte[]> classes, ClassLoader parent) {
        return new MemoryClassLoader(parent, classes);
    }

    /**
     * 创建加载器并解析 payload 的入口类（只解析，不初始化）。
     *
     * @param classes   类全名到字节码的映射
     * @param parent    父加载器
     * @param mainClass 入口类的全名
     * @return 已定义的入口类
     * @throws ClassNotFoundException 入口类不在映射中且父加载器也无法提供
     */
    public static Class<?> entryClass(Map<String, byte[]> classes, ClassLoader parent, String mainClass)
            throws ClassNotFoundException {
        return new MemoryClassLoader(parent, classes).loadClass(mainClass);
    }

    /**
     * 把流读完成一个字节数组（payload 必须整体解密，无法流式处理）。
     *
     * @param in 输入流，不会被关闭
     * @return 流内容的完整副本
     * @throws IOException 读取失败
     */
    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
