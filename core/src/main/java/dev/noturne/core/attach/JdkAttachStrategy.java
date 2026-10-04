package dev.noturne.core.attach;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 通过平台自带的 {@code com.sun.tools.attach.VirtualMachine} API 注入 agent。
 *
 * <p>JDK 9+ 上该类位于 {@code jdk.attach} 模块，默认即可达；JDK 8 上它位于
 * {@code lib/tools.jar}，而 tools.jar <em>不在</em>默认 classpath 上。子 ClassLoader 方案在
 * JDK 8 上<em>不可能</em>成功（{@code AttachProvider} 的 ServiceLoader 查找发生在系统类加载器里），
 * 因此 JDK 8 必须由 {@link ToolsJarBootstrap} 在入口处带 tools.jar 重启本 JVM；本类在缺失时
 * 直接抛出可诊断的 {@link ClassNotFoundException}，绝不静默失败。
 */
public final class JdkAttachStrategy implements AttachStrategy {

    /**
     * 策略名，用于诊断输出。
     *
     * @return 固定字符串 {@code "jdk-api"}
     */
    @Override
    public String name() {
        return "jdk-api";
    }

    /**
     * 把 agent jar 加载进 pid 指定的 JVM。
     *
     * @param pid       目标 JVM 的进程 id
     * @param agentJar  agent jar 路径，必须是已存在的文件
     * @param options   传给 {@code premain}/{@code agentmain} 的参数字串，可为 {@code null}（按空串处理）
     * @throws IllegalArgumentException agent jar 不存在
     * @throws Exception                 attach API 不可用、目标进程不存在或 agent 被拒绝加载
     */
    @Override
    public void attach(int pid, File agentJar, String options) throws Exception {
        if (!agentJar.isFile()) {
            throw new IllegalArgumentException("agent jar not found: " + agentJar);
        }
        Class<?> vmClass = virtualMachineClass();
        Method attachMethod = vmClass.getMethod("attach", String.class);
        Method loadAgent = vmClass.getMethod("loadAgent", String.class, String.class);
        Method detach = vmClass.getMethod("detach");

        Object vm;
        try {
            vm = attachMethod.invoke(null, Integer.toString(pid));
        } catch (InvocationTargetException e) {
            // 「目标进程不存在」等真实原因被包在反射异常里；解包后上层的 ClassNotFoundException
            // 提示与 describeFailure 映射才有机会命中。
            Throwable root = e.getCause();
            if (root instanceof Exception) {
                throw (Exception) root;
            }
            if (root instanceof Error) {
                throw (Error) root;
            }
            throw e;
        }
        try {
            loadAgent.invoke(vm, agentJar.getAbsolutePath(), options == null ? "" : options);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 抛出反射包装异常对用户毫无意义，这里把真实原因（进程不存在、agent jar 被拒等）透传出去。
            Throwable cause = e.getCause();
            if (isJdk8ResponseMismatch(cause)) {
                // 目标侧实际已加载成功，只是客户端读不懂 JDK 8 的响应格式（见方法注释）。
                return;
            }
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        } finally {
            try {
                detach.invoke(vm);
            } catch (Throwable ignored) {
                // detach 失败不能掩盖已成功的 load
            }
        }
    }

    /**
     * 判断异常是否为「JDK 9+ 客户端误读 JDK 8 目标响应」造成的假失败。
     *
     * <p>JDK 8 的 attach 协议在 {@code load} 命令上返回裸返回码（{@code 0\n}），
     * 而 JDK 9+ 的客户端按新格式（{@code return code: 0}）解析：整段响应不以
     * {@code return code: } 开头时，客户端抛
     * {@code AgentLoadException: Failed to load agent library: <原响应>}。
     * 实测（JDK 21 客户端 → JDK 8 目标，目标侧以「写文件」证实）：agent 已加载、
     * {@code agentmain} 已执行、返回码为 0 —— 因此把这种「返回码为 0 的 load 失败」视为成功。
     *
     * <p>不能用 {@code instanceof}：attach 类可能是从 tools.jar 的子加载器加载的，
     * 与编译期类型不同源；这里按异常类名与消息格式判定。
     *
     * @param cause {@code loadAgent} 反射调用抛出的原始异常
     * @return 目标侧实际加载成功时为 true
     */
    private static boolean isJdk8ResponseMismatch(Throwable cause) {
        if (cause == null
                || !"com.sun.tools.attach.AgentLoadException".equals(cause.getClass().getName())) {
            return false;
        }
        String message = cause.getMessage();
        String prefix = "Failed to load agent library: ";
        if (message == null || !message.startsWith(prefix)) {
            return false;
        }
        // 响应是裸返回码（JDK 8 格式）；0 表示 Agent_OnAttach 成功
        return "0".equals(message.substring(prefix.length()).trim());
    }

    /**
     * 解析 {@code com.sun.tools.attach.VirtualMachine}。
     *
     * @return attach API 的 Class 对象
     * @throws ClassNotFoundException classpath 上没有该类（JDK 8 需要先用 tools.jar 重启本 JVM）
     */
    private static Class<?> virtualMachineClass() throws Exception {
        try {
            // JDK 9+ 的 jdk.attach 模块，或已把 tools.jar 放进 classpath 的 JDK 8
            return Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (ClassNotFoundException notFound) {
            File toolsJar = locateToolsJar();
            if (toolsJar == null) {
                throw new ClassNotFoundException(
                        "com.sun.tools.attach.VirtualMachine is not available and no tools.jar was "
                                + "found under " + System.getProperty("java.home"), notFound);
            }
            // 注意：这里刻意不再用子 URLClassLoader。VirtualMachine 的静态初始化通过
            // ServiceLoader 在系统类加载器中查找 AttachProvider，子加载器里找不到 tools.jar
            // 的 provider，必然抛 ExceptionInInitializerError（同时那个 URLClassLoader 也永不关闭）。
            // 唯一可靠的做法是带 tools.jar 重启本 JVM，由 ToolsJarBootstrap 在入口处完成。
            throw new ClassNotFoundException(
                    "com.sun.tools.attach.VirtualMachine is not on the classpath; tools.jar exists at "
                            + toolsJar + " but a sub-classloader cannot provide the attach providers. "
                            + "Restart this JVM with tools.jar on the classpath (see ToolsJarBootstrap).",
                    notFound);
        }
    }

    /**
     * 定位 JDK 8 的 tools.jar：JDK 安装下位于 {@code $JAVA_HOME/lib}，从自带的 {@code jre}
     * 目录运行时位于 {@code $JAVA_HOME/../lib}。
     *
     * @return 第一个存在的 tools.jar 路径；本机没有时返回 {@code null}
     */
    static File locateToolsJar() {
        String home = System.getProperty("java.home");
        if (home == null) {
            return null;
        }
        File base = new File(home);
        File[] candidates = {
                new File(base, "lib/tools.jar"),
                new File(base, "../lib/tools.jar"),
                new File(base, "jre/lib/tools.jar"),
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }
}
