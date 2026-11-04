package dev.nocturne.client.control;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.event.PacketEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 包闸门的 FIFO、线程门和清理行为。 */
class PacketControllerTest {

    @BeforeEach
    void isolate() {
        TestSupport.resetClient();
    }

    @AfterEach
    void cleanUp() {
        TestSupport.resetClient();
    }

    @Test
    void heldPacketsAreCapturedAndDrainedWithoutReentry() {
        NocturneClient client = NocturneClient.boot(null);
        PacketController controller = client.packets();
        Object packet = new Object();

        assertTrue(controller.hold(PacketEvent.Direction.SEND));
        assertFalse(controller.hold(PacketEvent.Direction.SEND));
        assertTrue(controller.capture(PacketEvent.Direction.SEND, packet));
        assertEquals(1, controller.queued(PacketEvent.Direction.SEND));

        // 无动作绑定时发送明确失败，但队列必须排空且 bypass 不得泄漏。
        assertEquals(0, controller.release(PacketEvent.Direction.SEND));
        assertEquals(0, controller.queued(PacketEvent.Direction.SEND));
        assertFalse(controller.shouldBypass(packet));
    }

    @Test
    void offThreadSendReleaseKeepsGateAndQueueIntact() throws Exception {
        NocturneClient client = NocturneClient.boot(null);
        PacketController controller = client.packets();
        controller.noteMainThread();
        controller.hold(PacketEvent.Direction.SEND);
        controller.capture(PacketEvent.Direction.SEND, new Object());
        AtomicInteger released = new AtomicInteger(-1);

        Thread thread = new Thread(() -> released.set(controller.release(PacketEvent.Direction.SEND)));
        thread.start();
        thread.join();

        assertEquals(0, released.get());
        assertTrue(controller.isHolding(PacketEvent.Direction.SEND));
        assertEquals(1, controller.queued(PacketEvent.Direction.SEND));
        assertEquals(1, controller.clear(PacketEvent.Direction.SEND));
    }

    @Test
    void panicClearsBothQueuesAndGates() {
        NocturneClient client = NocturneClient.boot(null);
        PacketController controller = client.packets();
        controller.hold(PacketEvent.Direction.SEND);
        controller.hold(PacketEvent.Direction.RECEIVE);
        controller.capture(PacketEvent.Direction.SEND, new Object());
        controller.capture(PacketEvent.Direction.RECEIVE, new Object());

        client.panic();

        assertFalse(controller.isHolding(PacketEvent.Direction.SEND));
        assertFalse(controller.isHolding(PacketEvent.Direction.RECEIVE));
        assertEquals(0, controller.queued(PacketEvent.Direction.SEND));
        assertEquals(0, controller.queued(PacketEvent.Direction.RECEIVE));
    }
}
