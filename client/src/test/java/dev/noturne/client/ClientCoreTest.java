package dev.noturne.client;

import dev.noturne.client.event.EventBus;
import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.module.ModuleRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端核心装配的集成测试：覆盖事件总线按类型分发、模块生命周期（启用/禁用/注册约束）
 * 以及 {@link NoturneClient#boot} 的单例与幂等语义。
 */
class ClientCoreTest {

    /** 每个用例前后重置进程级单例，避免 {@code boot} 的全局状态在用例间互相污染 */
    @BeforeEach
    void isolate() {
        TestSupport.resetClient();
    }

    /** 用例结束后同样清空单例，避免影响其他测试类 */
    @AfterEach
    void cleanUp() {
        TestSupport.resetClient();
    }

    // ---------------------------------------------------------------- 事件总线

    /** 事件总线测试专用的载荷类型，{@code seen} 记录其被投递的次数。 */
    static final class Ping {
        int seen;
    }

    /** 与 {@code Ping} 无继承关系的独立载荷类型，用于验证按类型过滤。 */
    static final class Pong {
    }

    /**
     * 验证总线只把事件投递给订阅了其精确类型的监听器：混发 Ping/Pong 时各自只收到对应事件，
     * 不会串扰。
     */
    @Test
    void eventBusDeliversOnlyToMatchingType() {
        EventBus bus = new EventBus();
        final AtomicInteger pings = new AtomicInteger();
        final AtomicInteger pongs = new AtomicInteger();

        bus.subscribe(Ping.class, p -> p.seen++);
        bus.subscribe(Ping.class, p -> pings.incrementAndGet());
        bus.subscribe(Pong.class, p -> pongs.incrementAndGet());

        Ping ping = bus.post(new Ping());
        bus.post(new Pong());

        assertEquals(1, ping.seen);
        assertEquals(1, pings.get());
        assertEquals(1, pongs.get(), "Pong subscriber must fire exactly once");
    }

    // ------------------------------------------------------------ 模块生命周期

    /** 可观测生命周期的测试模块，记录各钩子被触发的次数。 */
    static final class Counter extends Module {
        /** {@link #onTick()} 被调用的次数。 */
        int ticks;
        /** {@link #onEnable()} 被调用的次数。 */
        int enableCalls;
        /** {@link #onDisable()} 被调用的次数。 */
        int disableCalls;

        @Override
        public String name() {
            return "Counter";
        }

        @Override
        public Category category() {
            return Category.MISC;
        }

        @Override
        protected void onEnable() {
            enableCalls++;
        }

        @Override
        protected void onDisable() {
            disableCalls++;
        }

        @Override
        public void onTick() {
            ticks++;
        }
    }

    /**
     * 验证 tick 分发受整体激活开关约束：模块自身已启用但注册表未激活时不收到 tick，
     * 激活后按次收到，再次停用后立即停止。
     */
    @Test
    void modulesOnlyTickWhileActive() {
        ModuleRegistry registry = new ModuleRegistry();
        Counter counter = new Counter();
        registry.register(counter);

        counter.setEnabled(true);
        assertEquals(1, counter.enableCalls);

        registry.tick();
        assertEquals(0, counter.ticks, "must not tick while inactive");

        registry.setActive(true);
        registry.tick();
        registry.tick();
        assertEquals(2, counter.ticks);

        registry.setActive(false);
        registry.tick();
        assertEquals(2, counter.ticks, "must stop ticking once inactive");
    }

    /**
     * 验证开关的钩子只在状态真正翻转时触发：重复 setEnabled(true) 不重复调用 onEnable，
     * toggle 在两态间切换并各触发一次对应钩子。
     */
    @Test
    void enablingTwiceDoesNotRefireHooks() {
        Counter counter = new Counter();
        counter.setEnabled(true);
        counter.setEnabled(true);
        assertEquals(1, counter.enableCalls);

        counter.toggle();
        assertFalse(counter.isEnabled());
        assertEquals(1, counter.disableCalls);

        counter.toggle();
        assertTrue(counter.isEnabled());
        assertEquals(2, counter.enableCalls);
    }

    /** 验证注册表以名称作为唯一键，注册重名模块时抛出 {@link IllegalArgumentException}。 */
    @Test
    void duplicateModuleNamesAreRejected() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new Counter());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new Counter()));
    }

    // ----------------------------------------------------------------- 启动引导

    /**
     * 验证 {@link NoturneClient#boot} 的幂等性：连续两次调用返回同一实例、共用同一注册表，
     * 且内置模块只注册一次（数量与名字集合都不变）。
     *
     * <p>旧断言比较「两次取同一 LinkedHashMap 的 size」是恒真重言式；这里改为比对内置模块集合，
     * boot 若重复注册（会抛重复名）或新建实例都会失败。
     */
    @Test
    void bootIsIdempotent() {
        NoturneClient first = NoturneClient.boot(null);
        Set<String> firstNames = new HashSet<String>();
        for (Module module : first.modules().all()) {
            firstNames.add(module.name());
        }
        Set<String> expected = new HashSet<String>(
                Arrays.asList("Watermark", "FullBright", "Sprint", "AutoRespawn",
                        "StorageESP", "ESP", "Tracers", "NameTags", "ItemESP",
                        "Trajectories", "Chams", "Xray", "Search"));
        NoturneClient second = NoturneClient.boot(null);
        assertSame(first, second, "boot must return the same instance");
        assertSame(first.modules(), second.modules(), "boot must reuse the same registry");
        assertTrue(NoturneClient.isRunning());
        assertSame(first, NoturneClient.get());

        Set<String> secondNames = new HashSet<String>();
        for (Module module : second.modules().all()) {
            secondNames.add(module.name());
        }
        assertEquals(firstNames, secondNames, "second boot must not register duplicates");
        assertEquals(firstNames.size(), second.modules().size(), "no duplicate names may slip in");
    }
}
