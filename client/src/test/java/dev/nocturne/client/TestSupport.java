package dev.nocturne.client;

import java.lang.reflect.Field;

/**
 * 测试辅助：重置 {@link NocturneClient} 的进程级单例，使依赖 {@code boot} 的用例彼此隔离。
 *
 * <p>生产代码没有（也不应有）重置入口，因此这里用最小反射宿主清空私有静态字段。
 */
public final class TestSupport {

    private TestSupport() {
    }

    /**
     * 把 {@link NocturneClient#instance} 置空；下一次 {@code boot} 会重新安装。
     *
     * <p>同时复位自销毁闸门与钩子能力表：{@code panic()} 会把闸门单向置位，若不在这里复位，
     * 同一 JVM 里后续用例的 GameplayHook 调用会全部退化成"放行"，看起来像事件系统坏了。
     */
    public static void resetClient() {
        dev.nocturne.bootstrap.GameplaySink.resetForTests();
        try {
            Field field = NocturneClient.class.getDeclaredField("instance");
            field.setAccessible(true);
            field.set(null, null);
        } catch (Throwable t) {
            throw new IllegalStateException("failed to reset NocturneClient singleton", t);
        }
    }
}
