package dev.noturne.core.attach;

/**
 * 识别当前 JVM 自身。
 *
 * <p>用于把注入器自身从「可注入目标」列表里排除掉（否则用户会看到并可能选中自己）。
 * 优先通过反射调用 Java 9 的 {@code ProcessHandle} API 以保证本类在 Java 8 上仍能加载，
 * 回退方案是 {@code RuntimeMXBean} 的进程名（形如 {@code 1234@hostname}）。
 */
public final class CurrentProcess {

    /** 无法确定 pid 时使用的哨兵值。 */
    private static final int UNKNOWN = -1;

    /** 工具类，禁止实例化。 */
    private CurrentProcess() {
    }

    /**
     * 判断给定 pid 是否就是当前 JVM。
     *
     * <p>当自身 pid 无法确定（{@link #pid()} 返回 {@link #UNKNOWN}）时返回 {@code false}——
     * 哨兵值 {@code -1} 绝不能被当成真实 pid 参与比较。
     *
     * @param candidate 待比较的进程 id
     * @return 候选 pid 与当前进程一致时为 {@code true}
     */
    public static boolean isSelf(int candidate) {
        int self = pid();
        return self > 0 && candidate == self;
    }

    /**
     * 返回当前 JVM 的进程 id。
     *
     * @return pid；无法确定时返回 {@link #UNKNOWN}（即 {@code -1}）
     */
    public static int pid() {
        try {
            // Java 9+：ProcessHandle.current().pid()
            Class<?> handleClass = Class.forName("java.lang.ProcessHandle");
            Object current = handleClass.getMethod("current").invoke(null);
            Object pid = handleClass.getMethod("pid").invoke(current);
            if (pid instanceof Number) {
                return ((Number) pid).intValue();
            }
        } catch (Throwable ignored) {
            // Java 8 或受限运行时：走下面的 RuntimeMXBean 回退路径
        }
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            // RuntimeMXBean 名字形如 "1234@hostname"，取 '@' 之前的部分
            int at = name.indexOf('@');
            return Integer.parseInt(at > 0 ? name.substring(0, at) : name);
        } catch (Throwable ignored) {
            return UNKNOWN;
        }
    }
}
