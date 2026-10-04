package dev.noturne.client.event;

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
 * 因此订阅者若触碰游戏状态需自行保证时序。订阅者抛出的异常不被隔离，会向上传播给发布方。
 */
public final class EventBus {

    /** 类型键 → 订阅者列表；列表为写时复制，故分发期间可安全地并发增删订阅。 */
    private final Map<Class<?>, List<Consumer<?>>> listeners =
            new ConcurrentHashMap<Class<?>, List<Consumer<?>>>();

    /**
     * 注册订阅者，允许多个订阅者订阅同一事件类型。
     *
     * @param type     订阅的事件类型（按精确运行时类匹配，不含父类匹配）
     * @param listener 订阅回调；为 {@code null} 时会注册空回调并在分发时抛 NPE，调用方须自行保证非空
     * <p>并发约束：多个线程订阅同一类型时，仅首个线程创建的列表会被后续线程复用（putIfAbsent）。
     */
    public <T> void subscribe(Class<T> type, Consumer<T> listener) {
        List<Consumer<?>> list = listeners.get(type);
        if (list == null) {
            List<Consumer<?>> created = new CopyOnWriteArrayList<Consumer<?>>();
            List<Consumer<?>> existing = listeners.putIfAbsent(type, created);
            list = existing != null ? existing : created;
        }
        list.add(listener);
    }

    /**
     * 注销订阅者。
     *
     * @param listener 之前传入的同一个回调实例；未注册过则无副作用
     * <p>只移除首个相等的实例。
     */
    public <T> void unsubscribe(Class<T> type, Consumer<T> listener) {
        List<Consumer<?>> list = listeners.get(type);
        if (list != null) {
            list.remove(listener);
        }
    }

    /**
     * 把事件投递给所有匹配的订阅者，并返回该事件以便链式调用。
     *
     * @param event 要分发的事件；不可为 {@code null}（会取其运行时类）
     * @return 传入的 {@code event} 本身
     * <p>同步执行：返回时所有订阅者已跑完。订阅顺序即注册顺序；遍历使用快照，
     * 订阅者在回调中取消自身不会影响本次分发。
     */
    @SuppressWarnings("unchecked")
    public <T> T post(T event) {
        List<Consumer<?>> list = listeners.get(event.getClass());
        if (list != null) {
            for (Consumer<?> listener : list) {
                ((Consumer<T>) listener).accept(event);
            }
        }
        return event;
    }
}
