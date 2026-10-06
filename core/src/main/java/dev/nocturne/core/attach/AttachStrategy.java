package dev.nocturne.core.attach;

import java.io.File;

/**
 * 把 agent jar 交给一个已在运行的 JVM 的一种方式。
 *
 * <p>实现按顺序尝试，第一个成功者胜出。这样既能让标准 JDK 路径保持最快，
 * 又为自包含路径留出空间 —— 影子化的 {@code sun.tools.attach.*} 字节码以及原生 attach 助手，
 * 用于覆盖缺少 {@code jdk.attach} / {@code tools.jar} 的 JRE。
 */
public interface AttachStrategy {

    /** 简短可读的策略名，用于诊断输出。 */
    String name();

    /**
     * 把 {@code agentJar} 载入 {@code pid} 标识的 JVM。
     *
     * @param pid       目标 JVM 的进程 id
     * @param agentJar  agent jar 路径
     * @param options   传给 {@code agentmain} 的参数字串，可为 {@code null}
     * @throws Exception 本策略不可用或执行失败；调用方会继续尝试下一个策略
     */
    void attach(int pid, File agentJar, String options) throws Exception;
}
