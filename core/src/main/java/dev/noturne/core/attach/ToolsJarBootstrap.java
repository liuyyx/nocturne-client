package dev.noturne.core.attach;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JDK 8 把 attach API 放在 {@code lib/tools.jar}，而它不在默认 classpath 上。
 * 仅仅用子 ClassLoader 加载该 jar 并不够：{@code AttachProvider} 的
 * {@link java.util.ServiceLoader} 查找会在那个子加载器内部进行，从而失败。
 *
 * <p>因此这里改为用「把 tools.jar 追加到 classpath」的参数重启本 JVM 一次。
 * 重启时保留原进程的全部 classpath 条目与 JVM 选项（{@code -X}/{@code -D}/{@code --add-opens} 等），
 * 否则多模块开发态启动的子进程会缺类。JDK 9+ 上 attach API 本来就可用，此时本类是空操作。
 */
public final class ToolsJarBootstrap {

    /** 用于探测 attach API 是否就绪的类名。 */
    private static final String ATTACH_CLASS = "com.sun.tools.attach.VirtualMachine";

    /** 标记「本进程已是重启后的进程」，防止 tools.jar 损坏时无限 fork。 */
    private static final String RELAUNCH_PROPERTY = "noturne.relaunched";

    /** 与 {@link #RELAUNCH_PROPERTY} 等价的进程环境变量（属性可能被启动脚本覆盖，环境变量更可靠）。 */
    private static final String RELAUNCH_ENV = "NOTURNE_RELAUNCHED";

    /** 工具类，禁止实例化。 */
    private ToolsJarBootstrap() {
    }

    /**
     * 若当前 JVM 缺少 attach API，则用带 tools.jar 的 classpath 重启自身一次。
     *
     * @param mainClass 新进程要执行的主类名
     * @param args      转发给新进程的命令行参数，可为 {@code null}
     * @return {@code true} 表示已启动替代进程，调用方必须立即返回（当前进程即将被放弃）；
     *         {@code false} 表示无需重启（attach API 已就绪 / 已是重启后的进程 / 装的是 JRE 无可加之物）
     * @throws RelaunchException 已决定重启但启动子进程失败；调用方应把它当作可诊断错误上报，
     *                           而不是继续走一条注定失败的 attach 路径
     */
    public static boolean relaunchIfNeeded(String mainClass, String[] args) {
        if (isAttachAvailable()) {
            return false;
        }
        if (isRelaunchedProcess()) {
            // 已经带 tools.jar 重启过一次仍然不可用：tools.jar 损坏或为空，
            // 此时绝不能再 fork，否则每个子进程都会再生成一个孙进程，形成无界进程创建循环。
            return false;
        }
        File toolsJar = JdkAttachStrategy.locateToolsJar();
        if (toolsJar == null) {
            return false; // 装的是 JRE：无可加之物，交给调用方报错
        }
        try {
            File self = CodeSources.toFile(ToolsJarBootstrap.class);
            File java = javaExecutable();
            List<String> command = new ArrayList<String>();
            command.add(java.getAbsolutePath());
            // 保留原 JVM 的 -X/-D/--add-opens 等选项，否则新进程的堆大小、模块开放、日志配置全部丢失。
            appendInputArguments(command);
            command.add("-D" + RELAUNCH_PROPERTY + "=true");
            command.add("-cp");
            command.add(classPath(self, toolsJar));
            command.add(mainClass);
            if (args != null) {
                command.addAll(Arrays.asList(args));
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put(RELAUNCH_ENV, "1");
            // 继承标准流，这样新进程的输出仍可见于当前控制台
            builder.inheritIO();
            builder.start();
            return true;
        } catch (Throwable t) {
            throw new RelaunchException(
                    "failed to relaunch this JVM with " + toolsJar + " on the classpath", t);
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

    /** 判断当前进程是否由 {@link #relaunchIfNeeded} 启动（系统属性或环境变量任一命中）。 */
    private static boolean isRelaunchedProcess() {
        if (Boolean.getBoolean(RELAUNCH_PROPERTY)) {
            return true;
        }
        return "1".equals(System.getenv(RELAUNCH_ENV));
    }

    /**
     * 组装子进程 classpath：原 classpath 的全部条目后追加 tools.jar。
     *
     * <p>原实现只保留本类所在的单个 code source，多模块/多 jar 启动时子进程必然缺类。
     */
    private static String classPath(File self, File toolsJar) {
        StringBuilder classPath = new StringBuilder();
        String current = System.getProperty("java.class.path");
        if (current == null || current.isEmpty()) {
            classPath.append(self.getAbsolutePath());
        } else {
            classPath.append(current);
        }
        // 原 classpath 未必含本类所在路径（例如由自定义加载器启动），缺失时补上。
        String selfPath = self.getAbsolutePath();
        if (classPath.indexOf(selfPath) < 0) {
            classPath.append(File.pathSeparator).append(selfPath);
        }
        classPath.append(File.pathSeparator).append(toolsJar.getAbsolutePath());
        return classPath.toString();
    }

    /**
     * 把当前 JVM 的输入参数（{@code -X}/{@code -D}/{@code --add-opens} 等）追加到子进程命令行。
     *
     * <p>只复制 JVM 输入参数；classpath 与主类由本方法自行重建，因此显式跳过相关选项。
     */
    private static void appendInputArguments(List<String> command) {
        List<String> input;
        try {
            RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
            input = bean.getInputArguments();
        } catch (Throwable t) {
            return; // 取不到输入参数时只保留默认，不影响重启本身
        }
        if (input == null) {
            return;
        }
        for (String arg : input) {
            if (arg == null || arg.isEmpty()) {
                continue;
            }
            if (isClassPathOption(arg) || arg.startsWith("-D" + RELAUNCH_PROPERTY + "=")) {
                continue;
            }
            command.add(arg);
        }
    }

    /** 判断某个 JVM 选项是否与 classpath / 主类相关，重启时应由本类重建而非原样复制。 */
    private static boolean isClassPathOption(String arg) {
        return arg.equals("-cp")
                || arg.equals("-classpath")
                || arg.equals("-jar")
                || arg.equals("--class-path")
                || arg.equals("--module-path")
                || arg.startsWith("-cp=")
                || arg.startsWith("-classpath=")
                || arg.startsWith("--class-path=")
                || arg.startsWith("--module-path=");
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
     * 重启失败（子进程无法启动）时抛出。
     *
     * <p>刻意使用非受检异常：{@code relaunchIfNeeded} 的既有调用方以布尔返回值判断是否已重启，
     * 这里通过异常把「尝试重启但失败」与「无需重启」区分开，且无需改动那些调用点。
     */
    public static final class RelaunchException extends RuntimeException {

        /** 序列化版本标识。 */
        private static final long serialVersionUID = 1L;

        /**
         * 构造重启失败异常。
         *
         * @param message 面向用户的诊断信息
         * @param cause   子进程启动失败的真实原因
         */
        public RelaunchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
