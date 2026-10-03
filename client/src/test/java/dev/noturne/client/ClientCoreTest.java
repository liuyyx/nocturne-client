package dev.noturne.client;

import dev.noturne.client.event.EventBus;
import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.module.ModuleRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCoreTest {

    // ---------------------------------------------------------------- event bus

    static final class Ping {
        int seen;
    }

    static final class Pong {
    }

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

    // ------------------------------------------------------------ module lifecycle

    static final class Counter extends Module {
        int ticks;
        int enableCalls;
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

    @Test
    void duplicateModuleNamesAreRejected() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new Counter());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new Counter()));
    }

    // ----------------------------------------------------------------- bootstrap

    @Test
    void bootIsIdempotent() {
        NoturneClient first = NoturneClient.boot(null);
        NoturneClient second = NoturneClient.boot(null);
        assertSame(first, second);
        assertTrue(NoturneClient.isRunning());
        assertEquals(first.modules().size(), second.modules().size());
    }
}
