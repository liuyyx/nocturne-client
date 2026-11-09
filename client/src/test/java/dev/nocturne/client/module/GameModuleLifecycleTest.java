package dev.nocturne.client.module;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.event.PacketEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册表挂起/恢复与模块启停交错时的幂等性。
 *
 * <p>回归：死亡界面挂起后重新启用模块，注册表的 suspended 列表仍持有它，重生恢复时会再调
 * 一次 onEnable，同一个订阅被安装两次（回调重复投递）。
 */
class GameModuleLifecycleTest {

    /** 只统计 onStart/onStop 次数，并真实订阅一个事件以暴露重复订阅。 */
    static final class Counting extends GameModule {
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();
        private final AtomicInteger deliveries = new AtomicInteger();

        Counting() {
            super("Counting", Category.MISC);
        }

        @Override
        protected void onStart() {
            starts.incrementAndGet();
            listen(PacketEvent.class, event -> deliveries.incrementAndGet());
        }

        @Override
        protected void onStop() {
            stops.incrementAndGet();
        }
    }

    @BeforeEach
    void isolate() {
        TestSupport.resetClient();
    }

    @AfterEach
    void cleanUp() {
        TestSupport.resetClient();
    }

    @Test
    void reEnableDuringSuspensionDoesNotResubscribeOnResume() {
        NocturneClient client = NocturneClient.boot(null);
        ModuleRegistry registry = new ModuleRegistry();
        Counting module = new Counting();
        registry.register(module);
        registry.setActive(true);

        module.setEnabled(true);
        assertEquals(1, module.starts.get());

        registry.setActive(false);
        assertEquals(1, module.stops.get(), "挂起按 onDisable 撤销副作用");

        // 死亡界面里关掉再打开：onStart 必须重新执行一次，但注册表仍把它记在 suspended 里。
        module.setEnabled(false);
        module.setEnabled(true);
        assertEquals(2, module.starts.get());

        registry.setActive(true);
        assertEquals(2, module.starts.get(), "恢复不得重复调用 onStart");
        assertEquals(1, module.stops.get(), "挂起期间超前一层的 onStop 已是幂等收尾，不得重复");

        client.events().post(new PacketEvent(PacketEvent.Direction.SEND, new Object()));
        assertEquals(1, module.deliveries.get(), "EventBus 中只能有一份订阅");
        assertTrue(registry.isActive());
    }

    /** 正常的挂起/恢复必须成对撤销并重装一次副作用，且 EventBus 里只保留一份订阅。 */
    @Test
    void gateFlipsResubscribeExactlyOnce() {
        NocturneClient client = NocturneClient.boot(null);
        ModuleRegistry registry = new ModuleRegistry();
        Counting module = new Counting();
        registry.register(module);
        registry.setActive(true);
        module.setEnabled(true);

        registry.setActive(false);
        registry.setActive(false);
        registry.setActive(true);
        registry.setActive(true);

        assertEquals(2, module.starts.get(), "挂起撤销一次、恢复重装一次");
        assertEquals(1, module.stops.get());
        client.events().post(new PacketEvent(PacketEvent.Direction.SEND, new Object()));
        assertEquals(1, module.deliveries.get(), "重复恢复不得留下双份订阅");
    }
}
