package dev.nocturne.client.control;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包事件与闸门的先后顺序。
 *
 * <p>回归：旧实现先 capture 再构造 PacketEvent，闸门一开所有订阅者的取消/替换逻辑全部静默
 * 失效；RECEIVE 事件还会在 Netty 线程直接分发。
 */
class PacketEventOrderTest {

    private static final class Recorder implements PacketController.Delivery {
        private final List<Object> sent = new ArrayList<Object>();
        private final List<Object> handled = new ArrayList<Object>();
        private Thread handledOn;

        @Override
        public boolean send(Object packet) {
            sent.add(packet);
            return true;
        }

        @Override
        public boolean handle(Object packet, Object connection) {
            handled.add(packet);
            handledOn = Thread.currentThread();
            return true;
        }
    }

    /** 安装最小桥与本地连接，使收包有 handler 可重放。 */
    private static void installConnection(NocturneClient client) {
        FakeMinecraft.reset();
        FakeMinecraft.instance.player = new FakePlayer();
        client.setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));
    }

    private final Object owner = new Object();

    @BeforeEach
    void isolate() {
        TestSupport.resetClient();
        GameplayHook.install(null);
    }

    @AfterEach
    void cleanUp() {
        GameplayHook.install(null);
        TestSupport.resetClient();
    }

    private PacketController controller(NocturneClient client, Recorder recorder) {
        PacketController packets = client.packets();
        packets.noteMainThread();
        packets.deliveryForTests(recorder);
        return packets;
    }

    /** 闸门开启时事件仍然先投递，未取消才入队。 */
    @Test
    void heldSendStillPostsTheEventBeforeQueueing() {
        NocturneClient client = NocturneClient.boot(null);
        Recorder recorder = new Recorder();
        PacketController packets = controller(client, recorder);
        List<PacketEvent> seen = new ArrayList<PacketEvent>();
        client.events().subscribe(PacketEvent.class, seen::add);
        packets.hold(PacketEvent.Direction.SEND, owner);

        Object packet = new Object();
        assertNull(GameplayHook.packetSend(packet), "hold 中的原调用仍被取消");
        assertEquals(1, seen.size(), "被压住的包也必须投递 PacketEvent");
        assertSame(packet, seen.get(0).packet);
        assertEquals(PacketEvent.Direction.SEND, seen.get(0).direction);
        assertEquals(1, packets.queued(PacketEvent.Direction.SEND));

        assertEquals(1, packets.release(PacketEvent.Direction.SEND, owner));
        assertSame(packet, recorder.sent.get(0));
    }

    /** 订阅者取消后不得再入队（否则取消语义变成延迟发送）。 */
    @Test
    void cancelledPacketIsNeverQueued() {
        NocturneClient client = NocturneClient.boot(null);
        Recorder recorder = new Recorder();
        PacketController packets = controller(client, recorder);
        client.events().subscribe(PacketEvent.class, event -> event.cancelled = true);
        packets.hold(PacketEvent.Direction.SEND, owner);

        assertNull(GameplayHook.packetSend(new Object()));
        assertEquals(0, packets.queued(PacketEvent.Direction.SEND));
        assertEquals(0, packets.release(PacketEvent.Direction.SEND, owner));
        assertTrue(recorder.sent.isEmpty());
    }

    /** setPacket 的替换对象必须是被压住并最终发出的那一个。 */
    @Test
    void replacedPacketIsTheOneQueuedAndSent() {
        NocturneClient client = NocturneClient.boot(null);
        Recorder recorder = new Recorder();
        PacketController packets = controller(client, recorder);
        Object replacement = new Object();
        client.events().subscribe(PacketEvent.class, event -> event.setPacket(replacement));
        packets.hold(PacketEvent.Direction.SEND, owner);

        assertNull(GameplayHook.packetSend(new Object()));
        assertEquals(1, packets.queued(PacketEvent.Direction.SEND));
        assertEquals(1, packets.release(PacketEvent.Direction.SEND, owner));
        assertSame(replacement, recorder.sent.get(0));
    }

    /** 未被 hold 的 RECEIVE：事件与（可能被取消的）原版处理都在主线程 drain 中完成。 */
    @Test
    void receiveEventsAreDeferredToTheMainThread() {
        NocturneClient client = NocturneClient.boot(null);
        Recorder recorder = new Recorder();
        PacketController packets = controller(client, recorder);
        installConnection(client);
        List<PacketEvent> seen = new ArrayList<PacketEvent>();
        client.events().subscribe(PacketEvent.class, seen::add);
        Object packet = new FakeClientPacket();

        assertNull(GameplayHook.packetReceive(packet), "收包处理统一推迟到主线程");
        assertTrue(seen.isEmpty(), "Netty 线程不得同步投递事件");

        client.gameplayEvents().drainReceive();
        assertEquals(1, seen.size());
        assertSame(packet, seen.get(0).packet);
        assertEquals(PacketEvent.Direction.RECEIVE, seen.get(0).direction);
        assertEquals(0, packets.queued(PacketEvent.Direction.RECEIVE));
        assertEquals(1, recorder.handled.size(), "未 hold 时由主线程补上原版处理");
        assertEquals(Thread.currentThread(), recorder.handledOn);
    }

    /** 被 hold 的 RECEIVE：事件先投递，仍未取消才入队；到达时原调用被取消。 */
    @Test
    void heldReceivePostsTheEventBeforeQueueing() {
        NocturneClient client = NocturneClient.boot(null);
        Recorder recorder = new Recorder();
        PacketController packets = controller(client, recorder);
        installConnection(client);
        List<PacketEvent> seen = new ArrayList<PacketEvent>();
        client.events().subscribe(PacketEvent.class, seen::add);
        packets.hold(PacketEvent.Direction.RECEIVE, owner);
        Object packet = new FakeClientPacket();

        assertNull(GameplayHook.packetReceive(packet), "hold 中收包被推迟");
        assertTrue(seen.isEmpty(), "事件必须等到主线程 drain");
        assertEquals(0, packets.queued(PacketEvent.Direction.RECEIVE), "事件投递前不得入队");

        client.gameplayEvents().drainReceive();
        assertEquals(1, seen.size());
        assertEquals(1, packets.queued(PacketEvent.Direction.RECEIVE));

        packets.release(PacketEvent.Direction.RECEIVE, owner);
        assertEquals(0, packets.queued(PacketEvent.Direction.RECEIVE));
        assertEquals(1, recorder.handled.size());
    }

    /** 被 hold 的 RECEIVE 若在主线程投递事件时被取消，则直接丢弃。 */
    @Test
    void heldReceiveCancelledBySubscriberIsDropped() {
        NocturneClient client = NocturneClient.boot(null);
        Recorder recorder = new Recorder();
        PacketController packets = controller(client, recorder);
        installConnection(client);
        client.events().subscribe(PacketEvent.class, event -> event.cancelled = true);
        packets.hold(PacketEvent.Direction.RECEIVE, owner);

        assertNull(GameplayHook.packetReceive(new FakeClientPacket()));
        client.gameplayEvents().drainReceive();

        assertEquals(0, packets.queued(PacketEvent.Direction.RECEIVE));
        assertEquals(0, packets.release(PacketEvent.Direction.RECEIVE, owner));
        assertTrue(recorder.handled.isEmpty());
    }

    /** 进世界前的登录/握手包没有 handler 可绑定，必须原样交还原版。 */
    @Test
    void preWorldPacketsPassThroughSynchronously() {
        NocturneClient client = NocturneClient.boot(null);
        PacketController packets = controller(client, new Recorder());
        int[] events = new int[1];
        client.events().subscribe(PacketEvent.class, event -> events[0]++);
        Object loginPacket = new Object();

        assertSame(loginPacket, GameplayHook.packetReceive(loginPacket));
        client.gameplayEvents().drainReceive();
        assertEquals(0, events[0], "登录阶段不投递模块事件");
        assertEquals(0, packets.queued(PacketEvent.Direction.RECEIVE));
    }

    /** 集成服务端的同一 Connection 钩子会收到 serverbound 包：必须原样放行，不得用客户端 handler 重放。 */
    @Test
    void serverboundPacketsPassThroughTheReceiveHook() {
        NocturneClient client = NocturneClient.boot(null);
        Recorder recorder = new Recorder();
        PacketController packets = controller(client, recorder);
        installConnection(client);
        int[] events = new int[1];
        client.events().subscribe(PacketEvent.class, event -> events[0]++);
        Object serverbound = new FakeServerPacket();

        assertSame(serverbound, GameplayHook.packetReceive(serverbound));
        client.gameplayEvents().drainReceive();
        assertEquals(0, events[0]);
        assertEquals(0, packets.queued(PacketEvent.Direction.RECEIVE));
        assertTrue(recorder.handled.isEmpty());
    }

    /** 控制包在 hold 中照常发送（事件仍会投递，取消语义保留）。 */
    @Test
    void controlPacketsPassThroughWhileHeld() {
        NocturneClient client = NocturneClient.boot(null);
        Recorder recorder = new Recorder();
        PacketController packets = controller(client, recorder);
        List<PacketEvent> seen = new ArrayList<PacketEvent>();
        client.events().subscribe(PacketEvent.class, seen::add);
        packets.hold(PacketEvent.Direction.SEND, owner);
        Object keepAlive = new FakeKeepAlive();

        assertSame(keepAlive, GameplayHook.packetSend(keepAlive));
        assertEquals(1, seen.size(), "控制包也要经过 PacketEvent");
        assertEquals(0, packets.queued(PacketEvent.Direction.SEND));
    }

    /** 1.8.9 的 C00PacketKeepAlive 类名（映射表不含该类）。 */
    static final class FakeKeepAlive {
    }

    /** 提供 {@code LocalPlayer.connection} 字段，使收包重放有连接身份可用。 */
    public static final class FakePlayer {
        public Object connection = new FakeClientListener() {
        };
    }

    /** clientbound 包的监听器类型；{@link FakeClientPacket#handle} 的形参。 */
    public interface FakeClientListener {
    }

    /** 最小 clientbound 包：方向判定只看 {@code handle(Listener)} 的形参类型。 */
    public static final class FakeClientPacket {
        public void handle(FakeClientListener listener) {
        }
    }

    /** 最小 serverbound 包：客户端连接不是它的 handler。 */
    public static final class FakeServerPacket {
        public void handle(FakeServerListener listener) {
        }
    }

    /** serverbound 包的监听器类型。 */
    public interface FakeServerListener {
    }
}
