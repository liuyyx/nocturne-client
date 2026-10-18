package dev.nocturne.core.attach;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 把 nocturne agent 载入一个正在运行的 JVM。
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

    /** 构造默认策略链：JDK attach API 打头，自实现通道按平台挂在后面。 */
    private static List<AttachStrategy> defaultStrategies() {
        List<AttachStrategy> strategies = new ArrayList<AttachStrategy>();
        strategies.add(new JdkAttachStrategy());
        // 自实现通道：Windows 是「命名管道 + 远线程桩投递」，Linux/macOS 是「直连目标的
        // 域套接字监听器」。两条实现都只在对应平台有原生库，因此按平台只挂一条，
        // 避免在错平台上产生一次必然失败的尝试。
        if (isWindows()) {
            strategies.add(new WindowsAttachStrategy());
        } else {
            strategies.add(new PosixAttachStrategy());
        }
        return Collections.unmodifiableList(strategies);
    }

    /** 当前是否 Windows（决定挂哪条自实现通道）。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
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
     * @return 实际成功的那条策略名（调用方可据此判断走的是 JDK API 还是自实现原生通道）
     * @throws AttachException 所有策略都失败时抛出，携带已尝试的策略名与最后一个失败原因
     */
    public static String attach(int pid, File agentJar, String options) throws AttachException {
        return attachWith(STRATEGIES, pid, agentJar, options);
    }

    /**
     * 用给定的策略链尝试注入（包可见：测试要在不改静态链的前提下验证失败语义）。
     *
     * @param strategies 按顺序尝试的策略列表
     * @param pid        目标进程 id
     * @param agentJar   agent jar 路径
     * @param options    传给 {@code agentmain} 的参数字串，可为 {@code null}
     * @return 实际成功的那条策略名
     * @throws AttachException 所有策略都失败时抛出
     */
    static String attachWith(List<AttachStrategy> strategies, int pid, File agentJar,
                             String options) throws AttachException {
        List<String> attempted = new ArrayList<String>();
        Throwable last = null;
        for (AttachStrategy strategy : strategies) {
            attempted.add(strategy.name());
            // 单个策略失败（类缺失、权限不足等）不应中断后续策略；
            // 但 VM 级错误（OOM、LinkageError 之外的内部错误）与中断必须向上传播。
            try {
                strategy.attach(pid, agentJar, options);
                return strategy.name();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                last = e;
                break;
            } catch (Exception e) {
                last = e;
            } catch (Error e) {
                // "这条策略在本机走不通"的 Error 也要继续尝试后面的策略：裁剪过的 JRE 上原生库
                // 加载失败抛 UnsatisfiedLinkError，JDK attach API 不在 classpath 时抛
                // NoClassDefFoundError。放它们逃出去，一次正常的多策略回退就变成整个工具崩掉，
                // 连"试试 tools.jar"这类可操作提示都打不出来（Nocturne 只捕 AttachException）。
                if (e instanceof VirtualMachineError || e instanceof ThreadDeath) {
                    // OOM / StackOverflow / InternalError / ThreadDeath：工具自身的问题或调用方
                    // 的终止请求，必须原样传播，否则会被伪装成"目标注入失败"。
                    throw e;
                }
                last = e;
            }
        }
        throw new AttachException(pid, attempted, last);
    }
}
