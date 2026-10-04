package dev.noturne.core.attach;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把 noturne agent 载入一个正在运行的 JVM。
 *
 * <p>策略按顺序尝试，第一个成功者胜出。标准 JDK attach API 排在最前，因为它最快且在存在时
 * 总是正确；面向精简 JRE 的自包含策略则通过同一个 {@link AttachStrategy} 接缝接在后面。
 */
public final class Attacher {

    /** 传给被注入一侧的 agent 参数前缀，其后携带本次会话的握手 token。 */
    public static final String OPTION_TOKEN = "token=";

    /** 策略列表，顺序即尝试顺序；不可变且线程安全。 */
    private static final List<AttachStrategy> STRATEGIES = defaultStrategies();

    /** 工具类，禁止实例化。 */
    private Attacher() {
    }

    /** 构造默认策略链（当前只有 JDK attach API 一条）。 */
    private static List<AttachStrategy> defaultStrategies() {
        List<AttachStrategy> strategies = new ArrayList<AttachStrategy>();
        strategies.add(new JdkAttachStrategy());
        // 后续阶段会追加：影子化的 sun.tools.attach.* 字节码（无需 tools.jar / jdk.attach），
        // 再是为精简 JRE 准备的原生 attach 助手。
        return Collections.unmodifiableList(strategies);
    }

    /**
     * 返回将要依次尝试的策略名。
     *
     * @return 与实际尝试顺序一致的策略名副本
     */
    public static List<String> strategyNames() {
        List<String> names = new ArrayList<String>(STRATEGIES.size());
        for (AttachStrategy strategy : STRATEGIES) {
            names.add(strategy.name());
        }
        return names;
    }

    /**
     * 按顺序尝试所有策略，把 agent jar 注入目标 JVM。
     *
     * @param pid       目标进程 id
     * @param agentJar  agent jar 路径
     * @param options   传给 {@code agentmain} 的参数字串，可为 {@code null}
     * @throws AttachException 所有策略都失败时抛出，携带已尝试的策略名与最后一个失败原因
     */
    public static void attach(int pid, File agentJar, String options) throws AttachException {
        List<String> attempted = new ArrayList<String>();
        Throwable last = null;
        for (AttachStrategy strategy : STRATEGIES) {
            attempted.add(strategy.name());
            // 单个策略失败（类缺失、权限不足等）不应中断后续策略
            try {
                strategy.attach(pid, agentJar, options);
                return;
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new AttachException(pid, attempted, last);
    }
}
