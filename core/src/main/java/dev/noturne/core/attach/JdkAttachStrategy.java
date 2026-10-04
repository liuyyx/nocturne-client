package dev.noturne.core.attach;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * 通过平台自带的 {@code com.sun.tools.attach.VirtualMachine} API 注入 agent。
 *
 * <p>JDK 9+ 上该类位于 {@code jdk.attach} 模块，默认即可达；JDK 8 上它位于
 * {@code lib/tools.jar}，而 tools.jar <em>不在</em>默认 classpath 上 —— 因此直接查找失败时，
 * 把该 jar 装进一个子 ClassLoader 再重试一次。这样既能让注入器在纯 JDK 8 下工作，
 * 又能在只装了 JRE（根本没有 tools.jar）时给出精确的错误信息。
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

        Object vm = attachMethod.invoke(null, Integer.toString(pid));
        try {
            loadAgent.invoke(vm, agentJar.getAbsolutePath(), options == null ? "" : options);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // 抛出反射包装异常对用户毫无意义，这里把真实原因（进程不存在、agent jar 被拒等）透传出去。
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
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
     * 解析 {@code com.sun.tools.attach.VirtualMachine}，失败时回退到 JDK 8 的 tools.jar。
     *
     * @return attach API 的 Class 对象
     * @throws ClassNotFoundException classpath 上没有该类且本机找不到 tools.jar（典型的「只有 JRE」场景）
     */
    private static Class<?> virtualMachineClass() throws Exception {
        try {
            // 首选：JDK 9+ 的 jdk.attach 模块，或已把 tools.jar 放进 classpath 的 JDK 8
            return Class.forName("com.sun.tools.attach.VirtualMachine");
        } catch (ClassNotFoundException notFound) {
            File toolsJar = locateToolsJar();
            if (toolsJar == null) {
                throw new ClassNotFoundException(
                        "com.sun.tools.attach.VirtualMachine is not available and no tools.jar was "
                                + "found under " + System.getProperty("java.home"), notFound);
            }
            // 回退：以当前加载器为父加载 tools.jar，使 attach API 至少可见
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{toolsJar.toURI().toURL()},
                    JdkAttachStrategy.class.getClassLoader());
            return Class.forName("com.sun.tools.attach.VirtualMachine", true, loader);
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
