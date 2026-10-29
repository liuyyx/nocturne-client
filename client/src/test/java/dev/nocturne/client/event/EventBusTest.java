package dev.nocturne.client.event;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventBus} 的分发与生命周期语义。
 *
 * <p>两条回归：
 * <ul>
 *   <li>High-20：可取消事件必须真的被总线执行——字段声明了"取消"却没有任何人检查它，
 *       等于给订阅者一个永远不生效的旋钮；</li>
 *   <li>High-21：退订必须清掉按订阅者身份键控的限流表——不清就永久强引用每个用过的订阅者，
 *       而模块每次启停都会新建 lambda，长时间运行是一条稳定增长的泄漏。</li>
 * </ul>
 */
class EventBusTest {

    /** 可取消的事件替身：与 {@link PacketEvent} 一样暴露一个可由订阅者置位的标志。 */
    private static final class CancellableEvent implements EventBus.Cancellable {
        private boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** 不可取消的普通事件。 */
    private static final class PlainEvent {
    }

    /** 置位取消标志的订阅者会让总线停止向后续订阅者投递。 */
    @Test
    void stopsDeliveringOnceACancellableEventIsCancelled() {
        EventBus bus = new EventBus();
        CancellableEvent event = new CancellableEvent();
        List<String> order = new ArrayList<String>();

        bus.subscribe(CancellableEvent.class, e -> {
            order.add("first");
            e.cancelled = true;
        });
        bus.subscribe(CancellableEvent.class, e -> order.add("second"));

        bus.post(event);

        assertEquals(java.util.Arrays.asList("first"), order,
                "listeners registered after the cancelling one must not run");
    }

    /** 普通事件照旧投给所有订阅者，不受取消语义影响。 */
    @Test
    void keepsDeliveringPlainEventsToEveryone() {
        EventBus bus = new EventBus();
        List<String> order = new ArrayList<String>();
        bus.subscribe(PlainEvent.class, e -> order.add("a"));
        bus.subscribe(PlainEvent.class, e -> order.add("b"));

        bus.post(new PlainEvent());

        assertEquals(java.util.Arrays.asList("a", "b"), order);
    }

    /** 一个订阅者抛异常不影响其余订阅者，也不把异常抛回调用方。 */
    @Test
    void isolatesListenerFailures() {
        EventBus bus = new EventBus();
        List<String> order = new ArrayList<String>();
        bus.subscribe(PlainEvent.class, e -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(PlainEvent.class, e -> order.add("survivor"));

        bus.post(new PlainEvent());

        assertEquals(java.util.Arrays.asList("survivor"), order);
    }

    /** 空事件被拒绝：{@code post(null)} 会让 {@code event.getClass()} 抛 NPE。 */
    @Test
    void rejectsNullEventsAndListeners() {
        EventBus bus = new EventBus();
        assertThrows(IllegalArgumentException.class, () -> bus.post(null));
        assertThrows(IllegalArgumentException.class, () -> bus.subscribe(null, e -> {
        }));
        assertThrows(IllegalArgumentException.class, () -> bus.subscribe(PlainEvent.class, null));
    }

    /** 退订后限流表里不得再留下该订阅者（否则就是永久强引用）。 */
    @Test
    void unsubscribeDropsTheThrottleEntry() throws Exception {
        EventBus bus = new EventBus();
        Consumer<String> broken = e -> {
            throw new IllegalStateException("boom");
        };
        EventBus.Subscription handle = bus.subscribe(String.class, broken);

        bus.post("trigger");   // 建立限流表条目
        assertTrue(throttleEntries(bus).size() >= 1, "precondition: the throttle entry exists");

        bus.unsubscribe(handle);

        assertTrue(throttleEntries(bus).isEmpty(),
                "unsubscribed listeners must not stay referenced by the throttle map");
    }

    /** 高优先级先执行，与注册先后无关。 */
    @Test
    void dispatchesHigherPriorityBeforeNormal() {
        EventBus bus = new EventBus();
        List<String> order = new ArrayList<String>();
        bus.subscribe(PlainEvent.class, EventBus.Priority.NORMAL, e -> order.add("normal"));
        bus.subscribe(PlainEvent.class, EventBus.Priority.HIGHEST, e -> order.add("highest"));

        bus.post(new PlainEvent());

        assertEquals(java.util.Arrays.asList("highest", "normal"), order);
    }

    /** 同优先级不能因快照重排而颠倒注册顺序。 */
    @Test
    void keepsRegistrationOrderWithinSamePriority() {
        EventBus bus = new EventBus();
        List<String> order = new ArrayList<String>();
        bus.subscribe(PlainEvent.class, EventBus.Priority.NORMAL, e -> order.add("first"));
        bus.subscribe(PlainEvent.class, EventBus.Priority.NORMAL, e -> order.add("second"));

        bus.post(new PlainEvent());

        assertEquals(java.util.Arrays.asList("first", "second"), order);
    }

    /** 中途取消只跳过默认订阅者；显式接收取消事件的低优先级订阅者仍执行。 */
    @Test
    void cancelledEventStillReachesOptInListeners() {
        EventBus bus = new EventBus();
        CancellableEvent event = new CancellableEvent();
        List<String> order = new ArrayList<String>();
        bus.subscribe(CancellableEvent.class, e -> {
            order.add("cancel");
            e.cancelled = true;
        });
        bus.subscribe(CancellableEvent.class, e -> order.add("skipped"));
        bus.subscribe(CancellableEvent.class, EventBus.Priority.LOWEST, true,
                e -> order.add("observer"));

        bus.post(event);

        assertEquals(java.util.Arrays.asList("cancel", "observer"), order);
    }

    /** 发布前已经取消的事件同样遵守订阅者的取消接收选择。 */
    @Test
    void preCancelledEventSkipsDefaultListeners() {
        EventBus bus = new EventBus();
        CancellableEvent event = new CancellableEvent();
        event.cancelled = true;
        List<String> order = new ArrayList<String>();
        bus.subscribe(CancellableEvent.class, e -> order.add("skipped"));
        bus.subscribe(CancellableEvent.class, EventBus.Priority.HIGHEST, true,
                e -> order.add("observer"));

        bus.post(event);

        assertEquals(java.util.Collections.singletonList("observer"), order);
    }

    /** 读私有的限流表；测试用它钉住"退订即释放"。 */
    private static Map<?, ?> throttleEntries(EventBus bus) throws Exception {
        Field field = EventBus.class.getDeclaredField("errorLogTimes");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(bus);
    }
}
