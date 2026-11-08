package dev.nocturne.agent;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * agent 侧的统一日志出口。
 *
 * <p>1.8.9 上 {@code System.out} 只进启动器控制台，不进 {@code logs/latest.log}；而真机验收时
 * 「是否注入成功」的唯一线索就在 latest.log 里。因此优先把日志交给游戏自己的 Log4j2：
 * 反射 {@code LogManager.getLogger("nocturne")} 并调用 {@code info(String)}；反射任何一步失败
 * （Log4j2 不在、版本 API 不同、拦截器拒绝）都退回 {@code System.out}——日志绝不能把注入搞崩。
 *
 * <p>为什么用反射而不是编译期依赖：agent jar 必须保持纯 Java 8 且不把 log4j 带进目标 JVM；
 * 目标侧的 Log4j2 是游戏自己的（1.8.9 上是 2.0-beta9），只共享接口名，不共享类。
 */
final class AgentLog {

    /** 所有 agent 日志的统一前缀，方便在游戏日志里检索。 */
    static final String PREFIX = "[nocturne] ";

    /** Log4j2 的两个入口类（目标游戏自带）。 */
    private static final String LOG4J_MANAGER = "org.apache.logging.log4j.LogManager";
    private static final String LOG4J_LOGGER = "org.apache.logging.log4j.Logger";

    /** 日志器名：Minecraft 的 latest.log 里会以这个名字出现。 */
    private static final String GAME_LOGGER_NAME = "nocturne";

    /** Log4j2 的 {@code Logger.info(String)}；解析成功后缓存。 */
    private static volatile Method infoMethod;

    /** Log4j2 日志器实例；与 {@link #infoMethod} 同时设置。 */
    private static volatile Object gameLogger;

    /** 测试替身：非 null 时日志只进这里，不写 Log4j2 / stdout。 */
    private static volatile Consumer<String> testSink;

    private AgentLog() {
    }

    /**
     * 输出一条带 {@code [nocturne]} 前缀的日志。
     *
     * @param message 日志正文；{@code null} 按 {@code "null"} 输出
     */
    static void info(String message) {
        String line = PREFIX + message;
        Consumer<String> sink = testSink;
        if (sink != null) {
            sink.accept(line);
            return;
        }
        if (writeToGameLog(line)) {
            return;
        }
        System.out.println(line);
    }

    /** 尝试写入游戏的 Log4j2；不可用时返回 false 让调用方退回 stdout。 */
    private static boolean writeToGameLog(String line) {
        Method method = infoMethod;
        Object logger = gameLogger;
        if (method == null || logger == null) {
            resolve();
            method = infoMethod;
            logger = gameLogger;
            if (method == null || logger == null) {
                return false;
            }
        }
        try {
            method.invoke(logger, line);
            return true;
        } catch (Throwable unavailable) {
            // 目标侧 Log4j2 卸载/替换了实现：这次退回 stdout，下次重新解析。
            return false;
        }
    }

    /**
     * 解析目标游戏的 Log4j2 日志器（幂等）。
     *
     * <p>依次尝试线程上下文加载器、本类加载器、系统加载器：Fabric/Forge 下 agent 的类加载器
     * 看不到游戏依赖，而 agentmain 执行时上下文加载器通常是游戏的隔离加载器。解析失败不缓存，
     * 后续调用（例如游戏加载器就绪后）会再试。
     */
    private static synchronized void resolve() {
        if (infoMethod != null && gameLogger != null) {
            return;
        }
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                AgentLog.class.getClassLoader(),
                ClassLoader.getSystemClassLoader(),
        };
        for (ClassLoader loader : loaders) {
            try {
                Class<?> manager = Class.forName(LOG4J_MANAGER, false, loader);
                Class<?> loggerType = Class.forName(LOG4J_LOGGER, false, loader);
                Object logger = manager.getMethod("getLogger", String.class)
                        .invoke(null, GAME_LOGGER_NAME);
                if (logger == null) {
                    continue;
                }
                infoMethod = loggerType.getMethod("info", String.class);
                gameLogger = logger;
                return;
            } catch (Throwable unavailable) {
                // 这个加载器看不到 Log4j2（或 API 形状不同），换下一个。
            }
        }
    }

    /**
     * 测试用：把日志重定向到内存收集器，并绕过 Log4j2/stdout。
     *
     * @param sink 收集器；{@code null} 恢复生产行为
     */
    static void setSinkForTests(Consumer<String> sink) {
        testSink = sink;
    }

    /**
     * 测试用：直接注入一个「已解析的 Log4j2 日志器」，用于验证
     * 「有 Log4j2 时不再写 stdout」这条分支（测试 JVM 里没有真的 Log4j2）。
     *
     * @param info   {@code Logger.info(String)}
     * @param logger 日志器实例
     */
    static void installGameLoggerForTests(Method info, Object logger) {
        infoMethod = info;
        gameLogger = logger;
    }

    /** 测试用：清空解析缓存与测试替身。 */
    static void resetForTests() {
        infoMethod = null;
        gameLogger = null;
        testSink = null;
    }
}
