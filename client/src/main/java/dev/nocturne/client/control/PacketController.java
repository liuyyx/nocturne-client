package dev.nocturne.client.control;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;

import java.util.EnumMap;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 双向包闸门与原序重放。
 *
 * <p>hold 只改变捕获状态；release(SEND) 与 sendDirectly 仅在客户端 tick 线程执行。
 * 接收包通过 Packet.handle(listener) 回到原版处理链，不再次投递 PacketEvent。
 * 世界切换和 panic 默认丢弃积压，避免把旧世界的位置/实体包放进新连接。
 */
public final class PacketController {

    private static final ThreadLocal<Boolean> BYPASSING = new ThreadLocal<Boolean>();

    private final NocturneClient client;
    private final EnumMap<PacketEvent.Direction, ConcurrentLinkedQueue<Captured>> queues =
            new EnumMap<PacketEvent.Direction, ConcurrentLinkedQueue<Captured>>(PacketEvent.Direction.class);
    private final EnumMap<PacketEvent.Direction, AtomicBoolean> holding =
            new EnumMap<PacketEvent.Direction, AtomicBoolean>(PacketEvent.Direction.class);
    private final Object drainLock = new Object();
    private final Set<String> warnings = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile Object pendingBypass;
    private volatile Thread mainThread;
    private boolean draining;

    public PacketController(NocturneClient client) {
        this.client = client;
        for (PacketEvent.Direction direction : PacketEvent.Direction.values()) {
            queues.put(direction, new ConcurrentLinkedQueue<Captured>());
            holding.put(direction, new AtomicBoolean());
        }
    }

    /** 开启指定方向的原序捕获；重复调用无副作用。 */
    public boolean hold(PacketEvent.Direction direction) {
        return direction != null && holding.get(direction).compareAndSet(false, true);
    }

    /** 释放指定方向的全部积压，返回实际处理的包数。 */
    public int release(PacketEvent.Direction direction) {
        if (direction == null) {
            return 0;
        }
        if (direction == PacketEvent.Direction.SEND && !onMainThread()) {
            warn("send-thread", "SEND packet release rejected off the client tick thread");
            return 0;
        }
        holding.get(direction).set(false);
        return drain(direction);
    }

    public int releaseAll() {
        return release(PacketEvent.Direction.SEND) + release(PacketEvent.Direction.RECEIVE);
    }

    /** 丢弃指定方向的积压，返回丢弃数量。 */
    public int clear(PacketEvent.Direction direction) {
        if (direction == null) {
            return 0;
        }
        ConcurrentLinkedQueue<Captured> queue = queues.get(direction);
        int count = 0;
        while (queue.poll() != null) {
            count++;
        }
        return count;
    }

    public int queued(PacketEvent.Direction direction) {
        return direction == null ? 0 : queues.get(direction).size();
    }

    public boolean isHolding(PacketEvent.Direction direction) {
        return direction != null && holding.get(direction).get();
    }

    /**
     * 在 SEND 闸门开启时立即发送一个时序敏感包；未开启时返回 false，调用方走普通 sendPacket。
     * 该入口只用于必须越过延迟队列的包，不作为通用的事件绕过 API。
     */
    public boolean sendDirectly(Object packet) {
        if (packet == null || !isHolding(PacketEvent.Direction.SEND) || !onMainThread()) {
            return false;
        }
        synchronized (drainLock) {
            return !draining && sendBypassed(packet, null, false);
        }
    }

    /** GameplayEvents 的直通检查；同一线程的嵌套 send 重载全部被覆盖。 */
    public boolean shouldBypass(Object packet) {
        return Boolean.TRUE.equals(BYPASSING.get()) || packet != null && packet == pendingBypass;
    }

    /** 闸门开启时捕获原包并返回 true；GameplayEvents 随即以 null 取消原调用。 */
    public boolean capture(PacketEvent.Direction direction, Object packet) {
        if (packet == null || !isHolding(direction)) {
            return false;
        }
        queues.get(direction).add(new Captured(packet, currentConnection()));
        return true;
    }

    /** 由 ModuleTicker 在客户端 tick 线程记录，防止 SEND 在其它线程释放。 */
    public void noteMainThread() {
        if (mainThread == null) {
            mainThread = Thread.currentThread();
        }
    }

    public void onWorldChange() {
        reset();
    }

    public void onPanic() {
        reset();
    }

    private void reset() {
        for (PacketEvent.Direction direction : PacketEvent.Direction.values()) {
            holding.get(direction).set(false);
            clear(direction);
        }
        pendingBypass = null;
    }

    private int drain(PacketEvent.Direction direction) {
        synchronized (drainLock) {
            if (draining) {
                return 0;
            }
            draining = true;
            int count = 0;
            try {
                Captured captured;
                while ((captured = queues.get(direction).poll()) != null) {
                    Object connection = currentConnection();
                    if (captured.connection != null && connection != captured.connection) {
                        clear(direction);
                        warn("connection", "captured packets discarded after connection change");
                        break;
                    }
                    if (sendBypassed(captured.packet, connection,
                            direction == PacketEvent.Direction.RECEIVE)) {
                        count++;
                    }
                }
            } finally {
                draining = false;
            }
            return count;
        }
    }

    /** 同步调用并在 finally 清理所有直通状态；不在此方法内调度异步任务。 */
    private boolean sendBypassed(Object packet, Object connection, boolean receive) {
        pendingBypass = packet;
        BYPASSING.set(Boolean.TRUE);
        try {
            if (receive) {
                GameBridge bridge = client.gameBridge();
                if (bridge == null || connection == null) {
                    warn("handle", "received packet replay skipped: bridge or listener unavailable");
                    return false;
                }
                return bridge.callMappedStatus(packet, ClassType.PACKET, "handle", connection);
            }
            return client.actions() != null && client.actions().sendPacket(packet);
        } catch (Throwable t) {
            warn("send", "packet release failed: " + t);
            return false;
        } finally {
            BYPASSING.set(Boolean.FALSE);
            pendingBypass = null;
        }
    }

    private Object currentConnection() {
        GameBridge bridge = client.gameBridge();
        return bridge == null ? null : bridge.connection();
    }

    private boolean onMainThread() {
        Thread expected = mainThread;
        return expected == null || expected == Thread.currentThread();
    }

    private void warn(String key, String message) {
        if (warnings.add(key)) {
            System.out.println("[nocturne] " + message);
        }
    }

    private static final class Captured {
        private final Object packet;
        private final Object connection;

        Captured(Object packet, Object connection) {
            this.packet = packet;
            this.connection = connection;
        }
    }
}
