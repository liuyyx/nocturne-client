package dev.noturne.client.event;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 极简的按类型索引的发布/订阅事件总线。
 *
 * <p>事件就是普通对象；订阅 {@code type} 的订阅者会收到所有运行时类恰好匹配 {@code type} 的事件。
 * 分发是同步的，且严格按订阅顺序执行，从而使游戏 tick 内的处理顺序保持确定。
 *
 * <p>线程约束：{@link #post(Object)} 可从任意线程调用，并会在调用线程上同步执行订阅者，
 * 因此订阅者若触碰游戏状态需自行保证时序。
 *
 * <p>故障隔离：单个订阅者抛出的任何 {@link Throwable} 都被捕获并记入限流日志，不会中止同一事件
 * 的其余订阅者，也不会把异常抛回发布方（发布方常位于游戏帧循环内）。
 */
public final class EventBus {

    /** 单个订阅者异常日志的限流窗口（毫秒）：同一订阅者在此窗口内最多打印一条异常日志。 */
    private static final long ERROR_LOG_INTERVAL_MS = 5000L;

    /** 类型键 → 订阅者列表；列表为写时复制，故分发期间可安全地并发增删订阅。 */
    private final Map<Class<?>, List<Consumer<?>>> listeners =
            new ConcurrentHashMap<Class<?>, List<Consumer<?>>>();
    /** 各订阅者上次打印异常的时间戳；值为单元素数组充当可变槽位，键为订阅者实例（身份语义）。 */
    private final Map<Consumer<?>, long[]> errorLogTimes = new ConcurrentHashMap<Consumer<?>, long[]>();

    /**
     * 订阅句柄：由 {@link #subscribe} 返回，持有它即可在之后精确退订。
     *
     * <p>存在意义：退订不能依赖“重建一个等价的 lambda 再传入”，因为两次 {@code () -> ...}
     * 是不同的对象（也即不同的订阅者）；句柄把“要移除的那个订阅者”这一身份固化下来。
     */
    public static final class Subscription {
        /** 订阅的事件类型。 */
        private final Class<?> type;
        /** 注册的订阅者实例。 */
        private final Consumer<?> listener;

        Subscription(Class<?> type, Consumer<?> listener) {
            this.type = type;
            this.listener = listener;
        }
    }

    /**
     * 注册订阅者，允许多个订阅者订阅同一事件类型。
     *
     * @param type     订阅的事件类型（按精确运行时类匹配，不含父类匹配），不可为 {@code null}
     * @param listener 订阅回调，不可为 {@code null}（注册空回调只会在分发时炸掉，故此处立即拒绝）
     * @return 订阅句柄，供 {@link #unsubscribe(Subscription)} 精确退订
     * @throws IllegalArgumentException {@code type} 或 {@code listener} 为 {@code null} 时抛出
     * <p>并发约束：多个线程订阅同一类型时，仅首个线程创建的列表会被后续线程复用（putIfAbsent）。
     */
    public <T> Subscription subscribe(Class<T> type, Consumer<T> listener) {
        if (type == null) {
            throw new IllegalArgumentException("event type must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        List<Consumer<?>> list = listeners.get(type);
        if (list == null) {
            List<Consumer<?>> created = new CopyOnWriteArrayList<Consumer<?>>();
            List<Consumer<?>> existing = listeners.putIfAbsent(type, created);
            list = existing != null ? existing : created;
        }
        list.add(listener);
        return new Subscription(type, listener);
    }

    /**
     * 按订阅句柄退订；句柄为 {@code null} 或已退订时无副作用。
     *
     * <p>推荐用法：{@code onEnable} 保存 {@link #subscribe} 的返回值，{@code onDisable} 传入该句柄。
     */
    public void unsubscribe(Subscription subscription) {
        if (subscription == null) {
            return;
        }
        unsubscribeRaw(subscription.type, subscription.listener);
    }

    /**
     * 按类型 + 订阅者实例退订，移除<b>全部</b>与该实例相等的条目。
     *
     * <p>注意：只有传入注册时用的<b>同一个</b>实例才有效；重新构造的等价 lambda 是不同对象。
     * 需要可靠退订时请改用 {@link #subscribe} 返回的 {@link Subscription}。
     *
     * @param type     注册时的类型；为 {@code null} 时无副作用
     * @param listener 之前传入的同一个回调实例；未注册过则无副作用
     */
    public <T> void unsubscribe(Class<T> type, Consumer<T> listener) {
        unsubscribeRaw(type, listener);
    }

    /**
     * 把事件投递给所有匹配的订阅者，并返回该事件以便链式调用。
     *
     * @param event 要分发的事件，不可为 {@code null}（会取其运行时类）
     * @return 传入的 {@code event} 本身
     * @throws IllegalArgumentException {@code event} 为 {@code null} 时抛出
     * <p>同步执行：返回时所有订阅者已跑完。订阅顺序即注册顺序；遍历使用快照，
     * 订阅者在回调中取消自身不会影响本次分发。订阅者抛出的异常被隔离并记入限流日志。
     */
    @SuppressWarnings("unchecked")
    public <T> T post(T event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        List<Consumer<?>> list = listeners.get(event.getClass());
        if (list == null) {
            return event;
        }
        for (Consumer<?> listener : list) {
            try {
                ((Consumer<T>) listener).accept(event);
            } catch (Throwable error) {
                // 逐订阅者隔离：一个订阅者出错不得中止其余订阅者，也不得把异常抛回游戏帧循环
                logListenerFailure(event.getClass(), listener, error);
            }
        }
        return event;
    }

    /** 从总线中移除指定类型的订阅者实例（全部相等条目）。 */
    private void unsubscribeRaw(Class<?> type, Consumer<?> listener) {
        if (type == null || listener == null) {
            return;
        }
        List<Consumer<?>> list = listeners.get(type);
        if (list != null) {
            list.removeAll(Collections.singleton(listener));
        }
    }

    /**
     * 记录订阅者异常，按订阅者限流（同一订阅者每 {@value #ERROR_LOG_INTERVAL_MS}ms 最多一条），
     * 兼顾“不刷屏”与“故障可检索”。
     */
    private void logListenerFailure(Class<?> eventType, Consumer<?> listener, Throwable error) {
        long now = System.currentTimeMillis();
        long[] slot = errorLogTimes.get(listener);
        if (slot == null) {
            slot = new long[]{0L};
            long[] existing = errorLogTimes.putIfAbsent(listener, slot);
            if (existing != null) {
                slot = existing;
            }
        }
        synchronized (slot) {
            if (now - slot[0] < ERROR_LOG_INTERVAL_MS) {
                return;
            }
            slot[0] = now;
        }
        System.out.println("[noturne] event listener failed for " + eventType.getName() + ": " + error);
        error.printStackTrace(System.out);
    }
}
