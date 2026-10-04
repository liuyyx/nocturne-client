package dev.noturne.client.runtime;

import dev.noturne.client.NoturneClient;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 运行时入口，由注入的字节码调用。
 *
 * <p>agent 会修改游戏主循环，使其每帧调用一次 {@link #onFrame()}。该方法位于热路径上，且运行在我们
 * 无法控制的 JVM 方法内部，因此刻意设计为防御式：绝不抛异常、绝不阻塞，且在注册监听器之前不做任何事。
 * 该类是各客户端模块接入游戏帧循环的统一挂载点，同时负责按 20Hz 驱动模块的 {@code tick()}。
 */
public final class NoturneRuntime {

    /** 每帧回调的监听器集合。采用写时复制列表，使帧线程遍历期间可安全增删。 */
    private static final CopyOnWriteArrayList<FrameListener> LISTENERS = new CopyOnWriteArrayList<FrameListener>();
    /** 是否打印帧钩子存活日志的开关，见 {@link #trace(boolean)}。 */
    private static final AtomicBoolean TRACE = new AtomicBoolean(false);
    /** 是否已记录过“首帧”日志，保证只打印一次（volatile 确保帧线程可见）。 */
    private static volatile boolean firstFrameLogged;

    /**
     * 同一线程重入保护：若某个监听器在回调里又触发了 {@link #onFrame()}，直接跳过，避免无限递归。
     * 使用 {@link ThreadLocal} 是因为“同一线程的嵌套调用”才是重入，跨线程属于下面锁的处理范围。
     */
    private static final ThreadLocal<Boolean> IN_FRAME = new ThreadLocal<Boolean>();
    /**
     * 跨来源驱动保护：agent 与模组两条路径可能同时存在，同帧的并发驱动只允许一个进行，
     * 其余直接跳过，避免开关键在一帧内被翻转两次（M-85）。用 {@code tryLock} 而非阻塞加锁，
     * 保证帧线程绝不阻塞。
     */
    private static final ReentrantLock FRAME_LOCK = new ReentrantLock();

    /** 异常日志限流计数，避免故障时每帧刷屏（M-84）。 */
    private static final AtomicLong ERROR_COUNT = new AtomicLong();

    /** 模块 tick 的目标周期（纳秒）：游戏逻辑 tick 约 20Hz，帧率远高于此，故按时间累加折算。 */
    private static final long TICK_INTERVAL_NANOS = 50_000_000L;
    /** 上次驱动模块 tick 的时间戳（纳秒）；0 表示尚未驱动过。 */
    private static volatile long lastTickNanos;

    /** 工具类，禁止实例化。 */
    private NoturneRuntime() {
    }

    /**
     * 注册一个每帧监听器。
     *
     * <p>幂等：重复注册同一实例（agent 与模组两条安装路径可能都注册同一个覆盖层）会被忽略，
     * 否则该监听器每帧会被驱动多次（M-83）。
     *
     * @param listener 待注册的监听器；为 {@code null} 时忽略
     */
    public static void addListener(FrameListener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    /**
     * 注销一个每帧监听器。
     *
     * @param listener 待移除的监听器；不存在时无副作用
     */
    public static void removeListener(FrameListener listener) {
        LISTENERS.remove(listener);
    }

    /** @return 当前已注册的监听器数量，主要用于诊断。 */
    public static int listenerCount() {
        return LISTENERS.size();
    }

    /**
     * 开启或关闭首帧存活日志。
     *
     * <p>钩子首次真正触发时打印一行日志，用于确认字节码补丁是否生效。
     *
     * @param enabled 是否启用
     */
    public static void trace(boolean enabled) {
        TRACE.set(enabled);
    }

    /**
     * 由注入的字节码每帧调用一次。
     *
     * <p>契约：无论发生什么都不得抛出异常，也不得阻塞。单个监听器抛出的任何 {@link Throwable} 都会被
     * 隔离并限流记录，以免一个损坏的覆盖层拖垮游戏或影响其他监听器。未注册任何监听器时仍会驱动模块
     * 逻辑 tick。处理完监听器后，按 20Hz 折算驱动 {@link NoturneClient#modules()} 的 {@code tick()}。
     */
    public static void onFrame() {
        // 同一线程重入：监听器内部再次触发帧回调时直接跳过，绝不递归。
        if (Boolean.TRUE.equals(IN_FRAME.get())) {
            reportThrottled("re-entrant onFrame ignored", null);
            return;
        }
        // 多来源保护：并发的第二个驱动直接跳过，保证同帧只跑一次。
        if (!FRAME_LOCK.tryLock()) {
            return;
        }
        IN_FRAME.set(Boolean.TRUE);
        try {
            // 只有在开启追踪时才消费“首帧”这一次机会，否则 trace(true) 之后永远等不到日志（M-82）。
            if (!firstFrameLogged && TRACE.get()) {
                firstFrameLogged = true;
                System.out.println("[noturne] frame hook is live");
            }
            // 写时复制列表的迭代器绑定单一数组快照：用增强 for，避免 size()/get(i) 读到不同快照。
            for (FrameListener listener : LISTENERS) {
                try {
                    listener.onFrame();
                } catch (Throwable t) {
                    reportThrottled("listener failed: " + listener.getClass().getName(), t);
                }
            }
            driveModules();
        } catch (Throwable t) {
            // 绝不把异常传播回被补丁的游戏方法；但仍留下限流日志，否则故障完全不可诊断（M-84）。
            reportThrottled("frame processing failed", t);
        } finally {
            IN_FRAME.remove();
            FRAME_LOCK.unlock();
        }
    }

    /**
     * 按游戏逻辑频率（约 20Hz）驱动模块 tick。
     *
     * <p>接口契约：模块注册表暴露无参的 {@code tick()}，自身负责激活闸门与逐模块异常隔离
     * （AutoRespawn 之类需要在死亡界面继续运行的模块由注册表豁免，见 H-34）。帧线程只做时间折算，
     * 不感知模块细节。客户端尚未 boot 时静默跳过。
     */
    private static void driveModules() {
        long now = System.nanoTime();
        if (lastTickNanos != 0L && now - lastTickNanos < TICK_INTERVAL_NANOS) {
            return;
        }
        lastTickNanos = now;
        NoturneClient client = NoturneClient.get();
        if (client == null) {
            return;
        }
        client.modules().tick();
    }

    /** 限流日志：前 5 次与之后每 600 次打印一条，避免故障时刷屏。 */
    private static void reportThrottled(String message, Throwable cause) {
        long count = ERROR_COUNT.incrementAndGet();
        if (count <= 5 || count % 600 == 0) {
            System.out.println("[noturne] onFrame: " + message + (cause == null ? "" : " (" + cause + ")"));
        }
    }
}
