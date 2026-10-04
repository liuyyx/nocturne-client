package dev.noturne.core.attach;

import java.io.File;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JDK 8 把 attach API 放在 {@code lib/tools.jar}，而它不在默认 classpath 上。
 * 仅仅用子 ClassLoader 加载该 jar 并不够：{@code AttachProvider} 的
 * {@link java.util.ServiceLoader} 查找会在那个子加载器内部进行，从而失败。
 *
 * <p>因此这里改为用「把 tools.jar 追加到 classpath」的参数重启本 JVM 一次。
 * JDK 9+ 上 attach API 本来就可用，此时本类是空操作。
 */
public final class ToolsJarBootstrap {

    /** 用于探测 attach API 是否就绪的类名。 */
    private static final String ATTACH_CLASS = "com.sun.tools.attach.VirtualMachine";

    /** 工具类，禁止实例化。 */
    private ToolsJarBootstrap() {
    }

    /**
     * 若当前 JVM 缺少 attach API，则用带 tools.jar 的 classpath 重启自身一次。
     *
     * @param mainClass 新进程要执行的主类名
     * @param args      转发给新进程的命令行参数，可为 {@code null}
     * @return {@code true} 表示已启动替代进程，调用方必须立即返回（当前进程即将被放弃）；
     *         {@code false} 表示无需重启（attach API 已就绪 / 是 JRE 无法重启 / 启动失败）
     */
    public static boolean relaunchIfNeeded(String mainClass, String[] args) {
        if (isAttachAvailable()) {
            return false;
        }
        File toolsJar = JdkAttachStrategy.locateToolsJar();
        if (toolsJar == null) {
            return false; // 装的是 JRE：无可加之物，交给调用方报错
        }
        try {
            File self = selfCodeSource();
            File java = javaExecutable();
            List<String> command = new ArrayList<String>();
            command.add(java.getAbsolutePath());
            command.add("-cp");
            command.add(self.getAbsolutePath() + File.pathSeparator + toolsJar.getAbsolutePath());
            command.add(mainClass);
            if (args != null) {
                command.addAll(Arrays.asList(args));
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            // 继承标准流，这样新进程的输出仍可见于当前控制台
            builder.inheritIO();
            builder.start();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 探测 attach API 是否已在当前 classpath 上可用。
     *
     * @return {@code true} 表示 {@code com.sun.tools.attach.VirtualMachine} 可直接加载
     */
    public static boolean isAttachAvailable() {
        try {
            Class.forName(ATTACH_CLASS);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 定位用于重启的 {@code java} 可执行文件；Windows 下优先 {@code java.exe}。 */
    private static File javaExecutable() {
        String home = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        File bin = new File(home, "bin");
        File executable = new File(bin, windows ? "java.exe" : "java");
        return executable.isFile() ? executable : new File(bin, "java");
    }

    /**
     * 取得本 jar 自身的文件系统路径（重启时作为 classpath 的第一项）。
     *
     * @throws Exception 代码源不可用或路径含非 UTF-8 字节时抛出
     */
    private static File selfCodeSource() throws Exception {
        String path = ToolsJarBootstrap.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        return new File(URLDecoder.decode(path, "UTF-8"));
    }
}
