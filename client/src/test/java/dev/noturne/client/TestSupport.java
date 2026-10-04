package dev.noturne.client;

import java.lang.reflect.Field;

/**
 * 测试辅助：重置 {@link NoturneClient} 的进程级单例，使依赖 {@code boot} 的用例彼此隔离。
 *
 * <p>生产代码没有（也不应有）重置入口，因此这里用最小反射宿主清空私有静态字段。
 */
public final class TestSupport {

    private TestSupport() {
    }

    /** 把 {@link NoturneClient#instance} 置空；下一次 {@code boot} 会重新安装。 */
    public static void resetClient() {
        try {
            Field field = NoturneClient.class.getDeclaredField("instance");
            field.setAccessible(true);
            field.set(null, null);
        } catch (Throwable t) {
            throw new IllegalStateException("failed to reset NoturneClient singleton", t);
        }
    }
}
