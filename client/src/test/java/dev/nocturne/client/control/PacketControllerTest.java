package dev.nocturne.client.control;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.event.PacketEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 包闸门的 FIFO、所有权、代次、线程门与控制包白名单。 */
class PacketControllerTest {

    /** 记录真实投递顺序的测试端口。 */
    private static final class Recorder implements PacketController.Delivery {
        private final List<Object> sent = new ArrayList<Object>();
        private final List<Object> handled = new ArrayList<Object>();

        @Override
        public boolean send(Object packet) {
            sent.add(packet);
            return true;
        }

        @Override
        public boolean handle(Object packet, Object connection) {
            handled.add(packet);
            return true;
        }
    }

    private final Object ownerA = new Object();
    private final Object ownerB = new Object();

    @BeforeEach
    void isolate() {
        TestSupport.resetClient();
    }

    @AfterEach
    void cleanUp() {
        TestSupport.resetClient();
    }

    private PacketController controller(Recorder recorder) {
        NocturneClient client = NocturneClient.boot(null);
        PacketController controller = client.packets();
        controller.noteMainThread();
        controller.deliveryForTests(recorder);
        return controller;
    }

    @Test
    void heldPacketsAreCapturedAndDrainedInOrder() {
        Recorder recorder = new Recorder();
        PacketController controller = controller(recorder);
        Object first = new Object();
        Object second = new Object();

        assertTrue(controller.hold(PacketEvent.Direction.SEND, ownerA));
        assertFalse(controller.hold(PacketEvent.Direction.SEND, ownerA), "同一 owner 重复 hold 幂等");
        assertTrue(controller.capture(PacketEvent.Direction.SEND, first));
        assertTrue(controller.capture(PacketEvent.Direction.SEND, second));
        assertEquals(2, controller.queued(PacketEvent.Direction.SEND));

        assertEquals(2, controller.release(PacketEvent.Direction.SEND, ownerA));
        assertEquals(0, controller.queued(PacketEvent.Direction.SEND));
        assertFalse(controller.isHolding(PacketEvent.Direction.SEND));
        assertEquals(first, recorder.sent.get(0));
        assertEquals(second, recorder.sent.get(1));
        // 重放完成后不得残留任何旁路状态。
        assertFalse(controller.shouldBypass(first));
        assertFalse(controller.shouldBypass(second));
    }

    /** 释放一个 owner 只清掉自己的所有权与包含自己的段，绝不动别人的独占包。 */
    @Test
    void releasingOneOwnerOnlyTouchesItsOwnSegments() {
        Recorder recorder = new Recorder();
        PacketController controller = controller(recorder);
        Object ownedByB = new Object();
        Object shared = new Object();
        Object ownedByA = new Object();

        controller.hold(PacketEvent.Direction.SEND, ownerB);
        controller.capture(PacketEvent.Direction.SEND, ownedByB);
        controller.hold(PacketEvent.Direction.SEND, ownerA);
        controller.capture(PacketEvent.Direction.SEND, shared);
        controller.capture(PacketEvent.Direction.SEND, ownedByA);
        assertEquals(3, controller.queued(PacketEvent.Direction.SEND));

        assertEquals(2, controller.release(PacketEvent.Direction.SEND, ownerA),
                "A 释放自己的独占包与共同持有的包，共同包只投递一次");
        assertEquals(shared, recorder.sent.get(0));
        assertEquals(ownedByA, recorder.sent.get(1));
        assertEquals(1, controller.queued(PacketEvent.Direction.SEND), "B 的独占包不得被 A 释放");
        assertTrue(controller.isHolding(PacketEvent.Direction.SEND, ownerB), "B 的闸门不受影响");
        assertFalse(controller.isHolding(PacketEvent.Direction.SEND, ownerA));

        assertEquals(1, controller.release(PacketEvent.Direction.SEND, ownerB));
        assertEquals(0, controller.queued(PacketEvent.Direction.SEND));
        assertFalse(controller.isHolding(PacketEvent.Direction.SEND));
        assertEquals(ownedByB, recorder.sent.get(2));
    }

    @Test
    void releaseSomeOnlyReleasesTheRequestingOwnersSegment() {
        Recorder recorder = new Recorder();
        PacketController controller = controller(recorder);
        Object ownedByB = new Object();
        Object shared = new Object();

        controller.hold(PacketEvent.Direction.SEND, ownerB);
        controller.capture(PacketEvent.Direction.SEND, ownedByB);
        controller.hold(PacketEvent.Direction.SEND, ownerA);
        controller.capture(PacketEvent.Direction.SEND, shared);

        // A 仍在 hold，slow release 只能放包含 A 的段；B 的独占包保持原位。
        assertEquals(1, controller.releaseSome(PacketEvent.Direction.SEND, ownerA, 5));
        assertEquals(1, controller.queued(PacketEvent.Direction.SEND));
        assertEquals(0, controller.queued(PacketEvent.Direction.SEND, ownerA));
        assertEquals(1, controller.queued(PacketEvent.Direction.SEND, ownerB));
        assertEquals(shared, recorder.sent.get(0));
        assertTrue(controller.isHolding(PacketEvent.Direction.SEND, ownerA));

        assertEquals(1, controller.release(PacketEvent.Direction.SEND, ownerB));
        assertEquals(ownedByB, recorder.sent.get(1));
    }

    @Test
    void offThreadReleaseClearsTheGateAndDefersTheDrain() throws Exception {
        Recorder recorder = new Recorder();
        PacketController controller = controller(recorder);
        Object packet = new Object();
        controller.hold(PacketEvent.Direction.SEND, ownerA);
        controller.capture(PacketEvent.Direction.SEND, packet);
        AtomicInteger released = new AtomicInteger(-1);

        Thread thread = new Thread(() -> released.set(controller.release(PacketEvent.Direction.SEND, ownerA)));
        thread.start();
        thread.join();

        assertEquals(0, released.get(), "异线程只撤销所有权，不直接投递");
        assertFalse(controller.isHolding(PacketEvent.Direction.SEND), "闸门不得永久卡住");
        assertEquals(1, controller.queued(PacketEvent.Direction.SEND));
        assertTrue(recorder.sent.isEmpty());

        controller.pump();
        assertEquals(1, recorder.sent.size(), "主线程 pump 必须放行异线程撤销的所有权");
        assertEquals(0, controller.queued(PacketEvent.Direction.SEND));
    }

    @Test
    void worldChangeEpochDropsOldCapturesAndResetsOwners() {
        Recorder recorder = new Recorder();
        PacketController controller = controller(recorder);
        Object packet = new Object();
        controller.hold(PacketEvent.Direction.SEND, ownerA);
        controller.hold(PacketEvent.Direction.RECEIVE, ownerB);
        controller.capture(PacketEvent.Direction.SEND, packet);
        controller.capture(PacketEvent.Direction.RECEIVE, new Object());
        long before = controller.epoch();

        controller.onWorldChange();

        assertNotEquals(before, controller.epoch(), "reset 必须推进代次");
        assertFalse(controller.isHolding(PacketEvent.Direction.SEND));
        assertFalse(controller.isHolding(PacketEvent.Direction.RECEIVE));
        assertEquals(0, controller.queued(PacketEvent.Direction.SEND));
        assertEquals(0, controller.queued(PacketEvent.Direction.RECEIVE));
        // 旧 owner 的 release 不得再放出任何旧世界包。
        assertEquals(0, controller.release(PacketEvent.Direction.SEND, ownerA));
        assertTrue(recorder.sent.isEmpty());
    }

    @Test
    void captureAfterWorldChangeGoesToTheNewEpoch() {
        Recorder recorder = new Recorder();
        PacketController controller = controller(recorder);
        controller.hold(PacketEvent.Direction.SEND, ownerA);
        controller.capture(PacketEvent.Direction.SEND, new Object());
        controller.onWorldChange();

        // reset 清掉了所有权：在 reset 之后、重新 hold 之前的 capture 必须直接放行。
        assertFalse(controller.capture(PacketEvent.Direction.SEND, new Object()));
        assertEquals(0, controller.queued(PacketEvent.Direction.SEND));

        controller.hold(PacketEvent.Direction.SEND, ownerA);
        Object fresh = new Object();
        assertTrue(controller.capture(PacketEvent.Direction.SEND, fresh));
        assertEquals(1, controller.release(PacketEvent.Direction.SEND, ownerA));
        assertEquals(fresh, recorder.sent.get(0));
    }

    @Test
    void panicClearsBothQueuesAndOwners() {
        Recorder recorder = new Recorder();
        NocturneClient client = NocturneClient.boot(null);
        PacketController controller = client.packets();
        controller.noteMainThread();
        controller.deliveryForTests(recorder);
        controller.hold(PacketEvent.Direction.SEND, ownerA);
        controller.hold(PacketEvent.Direction.RECEIVE, ownerB);
        controller.capture(PacketEvent.Direction.SEND, new Object());
        controller.capture(PacketEvent.Direction.RECEIVE, new Object());

        client.panic();

        assertFalse(controller.isHolding(PacketEvent.Direction.SEND));
        assertFalse(controller.isHolding(PacketEvent.Direction.RECEIVE));
        assertEquals(0, controller.queued(PacketEvent.Direction.SEND));
        assertEquals(0, controller.queued(PacketEvent.Direction.RECEIVE));
        assertTrue(recorder.sent.isEmpty());
        assertTrue(recorder.handled.isEmpty());
    }

    /** 控制面白名单：KeepAlive / TeleportConfirm / PlayerCommand / ContainerClose 永不被压。 */
    @Test
    void controlPacketsAreNeverCaptured() {
        Recorder recorder = new Recorder();
        PacketController controller = controller(recorder);
        controller.hold(PacketEvent.Direction.SEND, ownerA);
        controller.hold(PacketEvent.Direction.RECEIVE, ownerA);

        assertFalse(controller.capture(PacketEvent.Direction.SEND, new FakeKeepAlive()),
                "keepalive 被压会导致服务端超时");
        assertFalse(controller.capture(PacketEvent.Direction.SEND, new FakeConfirmTeleport()));
        assertFalse(controller.capture(PacketEvent.Direction.SEND, new FakePlayerCommand()));
        assertFalse(controller.capture(PacketEvent.Direction.SEND, new FakeContainerClose()));
        assertFalse(controller.capture(PacketEvent.Direction.RECEIVE, new FakeKeepAlive()));
        assertEquals(0, controller.queued(PacketEvent.Direction.SEND));
        assertEquals(0, controller.queued(PacketEvent.Direction.RECEIVE));

        assertTrue(controller.capture(PacketEvent.Direction.SEND, new Object()), "普通包照常入队");
    }

    /** 重放只旁路当前包：嵌套发送的其它包仍受闸门/事件系统约束。 */
    @Test
    void bypassIsScopedToOnePacket() {
        NocturneClient client = NocturneClient.boot(null);
        PacketController controller = client.packets();
        controller.noteMainThread();
        controller.hold(PacketEvent.Direction.SEND, ownerA);
        Object replay = new Object();
        Object nested = new Object();
        AtomicInteger replayBypassed = new AtomicInteger(-1);
        AtomicInteger nestedBypassed = new AtomicInteger(-1);
        controller.deliveryForTests(new PacketController.Delivery() {
            @Override
            public boolean send(Object packet) {
                replayBypassed.set(controller.shouldBypass(replay) ? 1 : 0);
                nestedBypassed.set(controller.shouldBypass(nested) ? 1 : 0);
                return true;
            }

            @Override
            public boolean handle(Object packet, Object connection) {
                return true;
            }
        });
        controller.capture(PacketEvent.Direction.SEND, replay);

        assertEquals(1, controller.release(PacketEvent.Direction.SEND, ownerA));
        assertEquals(1, replayBypassed.get(), "当前重放包必须旁路");
        assertEquals(0, nestedBypassed.get(), "嵌套的其它包不得被全局旁路覆盖");
        assertFalse(controller.shouldBypass(replay));
        assertFalse(controller.shouldBypass(nested));
    }

    /** 释放与在途捕获线性化：release 返回后不能再有无人认领的滞留包。 */
    @Test
    void concurrentCaptureAndReleaseLeaveNoStrandedPackets() throws Exception {
        Recorder recorder = new Recorder();
        PacketController controller = controller(recorder);
        controller.hold(PacketEvent.Direction.SEND, ownerA);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger captured = new AtomicInteger();
        Thread producer = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int i = 0; i < 500; i++) {
                if (controller.capture(PacketEvent.Direction.SEND, new Object())) {
                    captured.incrementAndGet();
                }
            }
        });
        producer.start();
        start.countDown();
        for (int i = 0; i < 500; i++) {
            controller.releaseSome(PacketEvent.Direction.SEND, ownerA, 1);
        }
        producer.join();
        controller.release(PacketEvent.Direction.SEND, ownerA);

        assertEquals(captured.get(), recorder.sent.size(),
                "每个捕获都必须被投递，不允许滞留或重复投递");
        assertEquals(0, controller.queued(PacketEvent.Direction.SEND));
        assertFalse(controller.isHolding(PacketEvent.Direction.SEND));
    }

    /** 收包过滤器按 owner 注册/移除，且只做通配判断。 */
    @Test
    void receiveFiltersAreOwnerScoped() {
        Recorder recorder = new Recorder();
        PacketController controller = controller(recorder);
        Object drop = new Object();
        controller.addReceiveFilter(ownerA, packet -> packet == drop);

        assertTrue(controller.filterReceive(drop));
        assertFalse(controller.filterReceive(new Object()));
        assertFalse(controller.filterReceive(null));

        controller.removeReceiveFilter(ownerA);
        assertFalse(controller.filterReceive(drop));
    }

    /** 1.8.9 的 C00PacketKeepAlive 也命中类名 token（映射表没有该类条目）。 */
    static final class FakeKeepAlive {
    }

    /** 1.9–1.16 的 CPacketConfirmTeleport。 */
    static final class FakeConfirmTeleport {
    }

    /** 现代 ServerboundPlayerCommandPacket 的类名 token 兜底。 */
    static final class FakePlayerCommand {
    }

    /** 现代 ServerboundContainerClosePacket 的类名 token 兜底。 */
    static final class FakeContainerClose {
    }
}
