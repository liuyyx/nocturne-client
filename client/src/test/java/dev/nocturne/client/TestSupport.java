package dev.nocturne.client;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试辅助：重置 {@link NocturneClient} 的进程级单例，使依赖 {@code boot} 的用例彼此隔离。
 *
 * <p>生产代码没有（也不应有）重置入口，因此这里用最小反射宿主清空私有静态字段。
 */
public final class TestSupport {

    /**
     * 测试专用配置目录：所有 boot 默认把模块配置写在这里，绝不在源码树/用户目录落盘。
     *
     * <p>需要验证持久化的用例可先调 {@link #resetClient()}，再把
     * {@code nocturne.config.dir} 指向自己的临时目录。
     */
    private static final Path CONFIG_DIR = createConfigDir();

    private TestSupport() {
    }

    /** @return 测试托管配置目录（每次 {@link #resetClient()} 会清空其内容） */
    public static Path configDir() {
        return CONFIG_DIR;
    }

    private static Path createConfigDir() {
        try {
            return Files.createTempDirectory("nocturne-test-config");
        } catch (IOException error) {
            return new java.io.File(System.getProperty("java.io.tmpdir", "."),
                    "nocturne-test-config").toPath();
        }
    }

    /**
     * 把 {@link NocturneClient#instance} 置空；下一次 {@code boot} 会重新安装。
     *
     * <p>同时复位自销毁闸门与钩子能力表：{@code panic()} 会把闸门单向置位，若不在这里复位，
     * 同一 JVM 里后续用例的 GameplayHook 调用会全部退化成"放行"，看起来像事件系统坏了。
     */
    public static void resetClient() {
        NocturneClient client = NocturneClient.get();
        if (client != null && client.moduleConfig() != null) {
            // 丢弃未落盘的变更并停掉去抖线程：否则上一个用例的保存会在下一个用例里“复活”。
            client.moduleConfig().close();
        }
        clearConfigFiles();
        System.setProperty("nocturne.config.dir", CONFIG_DIR.toString());
        dev.nocturne.bootstrap.GameplaySink.resetForTests();
        try {
            Field field = NocturneClient.class.getDeclaredField("instance");
            field.setAccessible(true);
            field.set(null, null);
        } catch (Throwable t) {
            throw new IllegalStateException("failed to reset NocturneClient singleton", t);
        }
    }

    private static void clearConfigFiles() {
        if (!Files.isDirectory(CONFIG_DIR)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CONFIG_DIR)) {
            for (Path file : stream) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // 单个文件删除失败不阻断用例：下一次 boot 仍会覆盖写入。
                }
            }
        } catch (IOException ignored) {
            // 目录不可读时保持原状，交给具体用例的断言暴露问题。
        }
    }
}
