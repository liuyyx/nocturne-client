package dev.nocturne.client.control;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双向包闸门与原序重放。
 *
 * <p><b>所有权</b>：hold/release 以调用方传入的 owner token（通常是模块实例）为单位，每个方向
 * 由多个 owner 同时持有也不会互相拆台——{@link #release(Direction, Object)} 只移除自己那一份，
 * 并且只放行“包含自己”的条目，绝不触碰别人的独占段；多 owner 共有的包由先决定放行的一方负责
 * 投递（只投递一次），因此两个延迟模块叠加时不会互相饿死。
 * {@link #releaseSome(Direction, Object, int)} 同样只放本 owner 的段。
 *
 * <p><b>世界切换</b>：{@link #onWorldChange()} 先在锁内递增 epoch 并清空所有 owner 与队列，
 * 调用方随后才投递 {@code WorldChangeEvent}；在途 capture 要么在 reset 前入队（随即被清空），
 * 要么在 reset 后看到空 owner 集合而直接返回 false，旧世界/旧连接的包不会重放进新世界。
 *
 * <p><b>线程</b>：capture 可从 Netty 线程（RECEIVE）或主线程（SEND）调用；放行（drain）与
 * SEND 释放只在客户端 tick 线程执行，非主线程的 release 只撤销闸门并把放行推迟到
 * {@link #pump()}（由 ModuleTicker 在主线程调用），闸门不会永远卡住。
 *
 * <p><b>旁路</b>：重放一个包时只用线程本地的“当前包身份”旁路这<b>一个</b>包，嵌套发送的其它包
 * 仍会走正常事件/闸门路径，不再有覆盖整段重放的全局直通开关。
 */
public final class PacketController {

    /** 收包同步过滤器：在 Netty 线程、原版处理之前求值；返回 true 表示丢弃该包。 */
    public interface ReceiveFilter {
        /**
         * 是否丢弃该包。实现必须线程安全且只做纯判断——它运行在 Netty 事件循环上，
         * 读取的共享状态必须自身并发安全（例如 ConcurrentHashMap 或 volatile 设置项）。
         */
        boolean drop(Object packet);
    }

    /** 包投递端口；生产实现走 ActionApi / 映射 handle，测试可替换以断言重放顺序。 */
    interface Delivery {
        boolean send(Object packet);

        boolean handle(Object packet, Object connection);
    }

    /** 收到的包在事件投递后的去向。 */
    private enum ReceiveMode {
        /** 到达时未被 hold：原版已在 Netty 线程处理，事件仅作观察。 */
        OBSERVE,
        /** 到达时被 hold 拦住：主线程投递事件后必须补上原版处理。 */
        GATED
    }

    private final NocturneClient client;
    private final EnumMap<PacketEvent.Direction, Gate> gates =
            new EnumMap<PacketEvent.Direction, Gate>(PacketEvent.Direction.class);
    /** 保护 owner 集合、队列与 epoch 的线性化点。 */
    private final Object stateLock = new Object();
    /** 串行化 drain；不覆盖 sendBypassed 的反射调用。 */
    private final Object drainLock = new Object();
    private final Set<String> warnings = ConcurrentHashMap.newKeySet();
    /** 当前线程正在重放的包身份；只旁路这一个包。 */
    private final ThreadLocal<Object> replaying = new ThreadLocal<Object>();
    /** 需要主线程泵的放行请求；跨线程写入，故用并发集合而非 EnumMap。 */
    private final Set<PacketEvent.Direction> pendingPump =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private volatile Thread mainThread;
    /** 世界/连接代次；reset 递增，drain 只接受同代次条目。 */
    private volatile long epoch;
    /** 主线程泵：被推迟的放行请求（非主线程 release 或 releaseSome 后的残留）。 */
    private volatile boolean pumpPending;
    private volatile FilterEntry[] receiveFilters = new FilterEntry[0];
    private boolean draining;
    private Delivery delivery;

    public PacketController(NocturneClient client) {
        this.client = client;
        for (PacketEvent.Direction direction : PacketEvent.Direction.values()) {
            gates.put(direction, new Gate());
        }
    }

    // ------------------------------------------------------------------ hold / release

    /**
     * 由 {@code owner} 开启指定方向的原序捕获。
     *
     * @return 本次调用是否新增了所有权（owner 已持有该方向时返回 false，且不重复计数）
     */
    public boolean hold(PacketEvent.Direction direction, Object owner) {
        if (direction == null || owner == null) {
            return false;
        }
        synchronized (stateLock) {
            return gates.get(direction).holders.put(owner, Boolean.TRUE) == null;
        }
    }

    /**
     * 释放 {@code owner} 在该方向的所有权并放行其可放行的包。
     *
     * <p> owns 与 queue 的“检查-入队”都在 {@link #stateLock} 内完成，因此不会出现“释放后
     * 才入队、再也没人 drain”的滞留包。
     *
     * @return 实际投递成功的包数；非主线程调用返回 0，放行推迟到下一次 {@link #pump()}
     */
    public int release(PacketEvent.Direction direction, Object owner) {
        if (direction == null || owner == null) {
            return 0;
        }
        synchronized (stateLock) {
            gates.get(direction).holders.remove(owner);
        }
        if (!onMainThread()) {
            requestPump(direction);
            return 0;
        }
        return drain(direction, owner, Integer.MAX_VALUE);
    }

    /**
     * 按数量原序释放 {@code owner} 自己捕获的那一段，保持该 owner 的闸门开启。
     *
     * <p>被其它仍持有的 owner 共同持有的条目不会被提前放行（保持其队列完整）。
     */
    public int releaseSome(PacketEvent.Direction direction, Object owner, int maximum) {
        if (direction == null || owner == null || maximum <= 0) {
            return 0;
        }
        if (!onMainThread()) {
            requestPump(direction);
            return 0;
        }
        return drain(direction, owner, maximum);
    }

    /** 撤销两个方向的全部所有权并放行全部可放行的条目，返回投递成功数。 */
    public int releaseAll() {
        synchronized (stateLock) {
            for (Gate gate : gates.values()) {
                gate.holders.clear();
            }
        }
        if (!onMainThread()) {
            requestPump(PacketEvent.Direction.SEND);
            requestPump(PacketEvent.Direction.RECEIVE);
            return 0;
        }
        return drain(PacketEvent.Direction.SEND, null, Integer.MAX_VALUE)
                + drain(PacketEvent.Direction.RECEIVE, null, Integer.MAX_VALUE);
    }

    /** 主线程泵：放行所有“已无 owner 持有”的积压，涵盖异线程 release 与残留段。 */
    public void pump() {
        if (!onMainThread() || !pumpPending) {
            return;
        }
        pumpPending = false;
        for (PacketEvent.Direction direction : PacketEvent.Direction.values()) {
            pendingPump.remove(direction);
            if (hasReleasable(direction)) {
                drain(direction, null, Integer.MAX_VALUE);
            }
        }
    }

    /** 丢弃指定方向的积压，返回丢弃数量。 */
    public int clear(PacketEvent.Direction direction) {
        if (direction == null) {
            return 0;
        }
        synchronized (stateLock) {
            Gate gate = gates.get(direction);
            int count = gate.queue.size();
            gate.queue.clear();
            return count;
        }
    }

    // ------------------------------------------------------------------ capture / query

    /** 该方向当前是否被任意 owner 持有。 */
    public boolean isHolding(PacketEvent.Direction direction) {
        if (direction == null) {
            return false;
        }
        synchronized (stateLock) {
            return !gates.get(direction).holders.isEmpty();
        }
    }

    /** {@code owner} 是否仍持有该方向。 */
    public boolean isHolding(PacketEvent.Direction direction, Object owner) {
        if (direction == null || owner == null) {
            return false;
        }
        synchronized (stateLock) {
            return gates.get(direction).holders.containsKey(owner);
        }
    }

    /** 指定方向积压总数（所有 owner 合计）。 */
    public int queued(PacketEvent.Direction direction) {
        if (direction == null) {
            return 0;
        }
        synchronized (stateLock) {
            return gates.get(direction).queue.size();
        }
    }

    /** 指定方向中由 {@code owner} 捕获的积压数（多 owner 条目不重复计数）。 */
    public int queued(PacketEvent.Direction direction, Object owner) {
        if (direction == null || owner == null) {
            return 0;
        }
        synchronized (stateLock) {
            int count = 0;
            for (Captured captured : gates.get(direction).queue) {
                if (captured.owns(owner)) {
                    count++;
                }
            }
            return count;
        }
    }

    /** 当前代次；GameplayEvents 用它丢弃跨世界重置的延迟收包。 */
    public long epoch() {
        return epoch;
    }

    /** 当前连接对象；用于跨世界/重连的积压校验。 */
    public Object connection() {
        GameBridge bridge = client.gameBridge();
        return bridge == null ? null : bridge.connection();
    }

    /**
     * 闸门开启且包不是控制包时捕获原包并返回 true；调用方随即以 null 取消原调用。
     *
     * <p>KeepAlive / TeleportConfirm / PlayerCommand / ContainerClose 永不出队：压住它们会让
     * 服务端 keepalive 超时、传送确认缺失并导致回弹，属于控制面而非可延迟的玩法流量。
     */
    public boolean capture(PacketEvent.Direction direction, Object packet) {
        if (direction == null || packet == null || isControlPacket(packet)) {
            return false;
        }
        GameBridge bridge = client.gameBridge();
        Object connection = bridge == null ? null : bridge.connection();
        synchronized (stateLock) {
            Gate gate = gates.get(direction);
            if (gate.holders.isEmpty()) {
                return false;
            }
            gate.queue.addLast(new Captured(packet, connection, epoch, gate.holders));
            return true;
        }
    }

    /**
     * 在 SEND 闸门开启时立即发送一个时序敏感包；未开启时返回 false，调用方走普通 sendPacket。
     */
    public boolean sendDirectly(Object packet) {
        if (packet == null || !isHolding(PacketEvent.Direction.SEND) || !onMainThread()) {
            return false;
        }
        synchronized (drainLock) {
            if (draining) {
                return false;
            }
            draining = true;
            try {
                return sendNow(packet);
            } finally {
                draining = false;
            }
        }
    }

    /**
     * 在主线程补做一次原版收包处理（未进闸门的包）。
     *
     * <p>与 {@link #release} 的 RECEIVE 重放共用同一投递端口，保证两条路径的顺序/失败语义一致。
     */
    public boolean handleReceived(Object packet, Object connection) {
        return handleReceive(packet, connection);
    }

    /** GameplayEvents 的直通检查：只匹配当前线程正在重放的那一个包。 */
    public boolean shouldBypass(Object packet) {
        return packet != null && replaying.get() == packet;
    }

    // ------------------------------------------------------------------ 收包过滤器

    /** 注册收包同步过滤器；同一 owner 重复注册会替换旧实例。 */
    public synchronized void addReceiveFilter(Object owner, ReceiveFilter filter) {
        if (owner == null || filter == null) {
            return;
        }
        java.util.List<FilterEntry> next = new java.util.ArrayList<FilterEntry>();
        for (FilterEntry existing : receiveFilters) {
            if (existing.owner != owner) {
                next.add(existing);
            }
        }
        next.add(new FilterEntry(owner, filter));
        receiveFilters = next.toArray(new FilterEntry[0]);
    }

    /** 移除 {@code owner} 注册的收包过滤器。 */
    public synchronized void removeReceiveFilter(Object owner) {
        if (owner == null) {
            return;
        }
        java.util.List<FilterEntry> next = new java.util.ArrayList<FilterEntry>();
        for (FilterEntry existing : receiveFilters) {
            if (existing.owner != owner) {
                next.add(existing);
            }
        }
        receiveFilters = next.toArray(new FilterEntry[0]);
    }

    /** 任一收包过滤器要求丢弃时为 true；在 Netty 线程上调用。 */
    public boolean filterReceive(Object packet) {
        if (packet == null) {
            return false;
        }
        FilterEntry[] snapshot = receiveFilters;
        for (FilterEntry entry : snapshot) {
            if (entry.filter.drop(packet)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ reset

    /** 世界身份变化：先清空闸门与队列并推进 epoch，随后调用方才投递 WorldChangeEvent。 */
    public void onWorldChange() {
        reset();
    }

    /** panic：与换世界相同的丢弃语义。 */
    public void onPanic() {
        reset();
    }

    private void reset() {
        synchronized (stateLock) {
            epoch++;
            for (Gate gate : gates.values()) {
                gate.holders.clear();
                gate.queue.clear();
            }
            pumpPending = false;
            pendingPump.clear();
            replaying.remove();
        }
        // replaying 是线程私有的：调用线程清掉即可；其它线程在途 drain 靠 epoch 不匹配丢弃。
    }

    // ------------------------------------------------------------------ drain

    private int drain(PacketEvent.Direction direction, Object owner, int maximum) {
        synchronized (drainLock) {
            if (draining) {
                return 0;
            }
            draining = true;
        }
        int sent = 0;
        try {
            while (sent < maximum) {
                Captured captured;
                synchronized (stateLock) {
                    Gate gate = gates.get(direction);
                    captured = takeNextLocked(direction, gate, owner);
                    if (captured == null) {
                        break;
                    }
                    if (captured.epoch != epoch) {
                        continue;
                    }
                }
                GameBridge bridge = client.gameBridge();
                Object connection = bridge == null ? null : bridge.connection();
                if (captured.connection != null && connection != captured.connection) {
                    reset();
                    warn("connection", "captured packets discarded after connection change");
                    break;
                }
                if (deliver(direction, captured, connection)) {
                    sent++;
                }
            }
        } finally {
            synchronized (drainLock) {
                draining = false;
            }
        }
        return sent;
    }

    /**
     * 在持有 {@link #stateLock} 时挑选下一个可放行条目。
     *
     * <p>owner 非 {@code null}：只取包含该 owner 的条目，且不再等待其它 owner——共有包由先释放
     * 的一方负责投递，避免嵌套延迟互相饿死；owner 为 {@code null}（pump/releaseAll）：只取所有
     * owner 都已释放的条目。过代次条目直接移除。
     */
    private Captured takeNextLocked(PacketEvent.Direction direction, Gate gate, Object owner) {
        for (Iterator<Captured> iterator = gate.queue.iterator(); iterator.hasNext(); ) {
            Captured captured = iterator.next();
            if (captured.epoch != epoch) {
                iterator.remove();
                continue;
            }
            if (owner != null) {
                if (!captured.owns(owner)) {
                    continue;
                }
            } else if (!releasedLocked(gate.holders, captured)) {
                continue;
            }
            iterator.remove();
            return captured;
        }
        return null;
    }

    /** owner 为 null 的放行（pump）只处理所有持有者都已释放的条目。 */
    private boolean releasedLocked(IdentityHashMap<Object, Boolean> holders, Captured captured) {
        for (int i = 0; i < captured.ownerCount(); i++) {
            if (holders.containsKey(captured.ownerAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasReleasable(PacketEvent.Direction direction) {
        synchronized (stateLock) {
            Gate gate = gates.get(direction);
            for (Captured captured : gate.queue) {
                if (captured.epoch == epoch && releasedLocked(gate.holders, captured)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean deliver(PacketEvent.Direction direction, Captured captured, Object connection) {
        if (direction == PacketEvent.Direction.RECEIVE) {
            return handleReceive(captured.packet, connection);
        }
        return sendNow(captured.packet);
    }

    /** 只旁路当前这一个包；嵌套发送的其它包仍走事件与闸门。 */
    private boolean sendNow(Object packet) {
        Delivery port = delivery;
        Object previous = replaying.get();
        replaying.set(packet);
        try {
            if (port != null) {
                return port.send(packet);
            }
            return client.actions() != null && client.actions().sendPacket(packet);
        } catch (Throwable error) {
            warn("send", "packet release failed: " + error);
            return false;
        } finally {
            if (previous == null) {
                replaying.remove();
            } else {
                replaying.set(previous);
            }
        }
    }

    /** 收包重放走映射的 Packet.handle(listener)，不会再次触发 channelRead0 钩子。 */
    private boolean handleReceive(Object packet, Object connection) {
        if (connection == null) {
            warn("handle", "received packet replay skipped: bridge or listener unavailable");
            return false;
        }
        Delivery port = delivery;
        try {
            if (port != null) {
                return port.handle(packet, connection);
            }
            GameBridge bridge = client.gameBridge();
            return bridge != null
                    && bridge.callMappedStatus(packet, ClassType.PACKET, "handle", connection);
        } catch (Throwable error) {
            warn("handle", "received packet replay failed: " + error);
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    private void requestPump(PacketEvent.Direction direction) {
        pendingPump.add(direction);
        pumpPending = true;
    }

    /** 由 ModuleTicker 在客户端 tick 线程记录，防止 SEND 在其它线程直接投递。 */
    public void noteMainThread() {
        if (mainThread == null) {
            mainThread = Thread.currentThread();
        }
    }

    private boolean onMainThread() {
        Thread expected = mainThread;
        return expected == null || expected == Thread.currentThread();
    }

    /**
     * 控制面白名单：KeepAlive / TeleportConfirm / PlayerCommand / ContainerClose。
     *
     * <p>前两者没有映射表条目（各版本的类名差异在大版本里是
     * {@code C00PacketKeepAlive}/{@code ServerboundKeepAlivePacket}、
     * {@code CPacketConfirmTeleport}/{@code ServerboundAcceptTeleportationPacket}），
     * 因此按类名 token 判定；后两者走映射身份，1.8.9 的别名同样命中。
     */
    private boolean isControlPacket(Object packet) {
        GameBridge bridge = client.gameBridge();
        if (bridge != null && (bridge.isInstance(packet, ClassType.SERVERBOUND_CONTAINER_CLOSE)
                || bridge.isInstance(packet, ClassType.SERVERBOUND_PLAYER_COMMAND))) {
            return true;
        }
        String name = packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return name.contains("keepalive")
                || name.contains("confirmteleport")
                || name.contains("acceptteleportation")
                || name.contains("teleportconfirm")
                || name.contains("playercommand")
                || name.contains("containerclose");
    }

    void deliveryForTests(Delivery port) {
        this.delivery = port;
    }

    private void warn(String key, String message) {
        if (warnings.add(key)) {
            System.out.println("[nocturne] " + message);
        }
    }

    /** 收包过滤器条目：owner 身份 + 纯判断实现。 */
    private static final class FilterEntry {
        private final Object owner;
        private final ReceiveFilter filter;

        private FilterEntry(Object owner, ReceiveFilter filter) {
            this.owner = owner;
            this.filter = filter;
        }
    }

    /** 每个方向的闸门状态：owner 集合 + 有序积压。 */
    private static final class Gate {
        private final IdentityHashMap<Object, Boolean> holders =
                new IdentityHashMap<Object, Boolean>();
        private final ArrayDeque<Captured> queue = new ArrayDeque<Captured>();
    }

    /** 一次捕获：记录包、连接身份、代次与捕获时的 owner 快照。 */
    private static final class Captured {
        private final Object packet;
        private final Object connection;
        private final long epoch;
        /** 单 owner 快路径；多 owner 时为 null。 */
        private final Object owner;
        private final Object[] owners;
        private final int ownerCount;

        Captured(Object packet, Object connection, long epoch,
                 IdentityHashMap<Object, Boolean> holders) {
            this.packet = packet;
            this.connection = connection;
            this.epoch = epoch;
            int size = holders.size();
            if (size == 1) {
                Object single = null;
                for (Object held : holders.keySet()) {
                    single = held;
                }
                this.owner = single;
                this.owners = null;
                this.ownerCount = 1;
            } else {
                this.owner = null;
                this.owners = holders.keySet().toArray(new Object[size]);
                this.ownerCount = size;
            }
        }

        boolean owns(Object candidate) {
            if (owner != null) {
                return owner == candidate;
            }
            for (Object held : owners) {
                if (held == candidate) {
                    return true;
                }
            }
            return false;
        }

        int ownerCount() {
            return ownerCount;
        }

        Object ownerAt(int index) {
            return owner != null ? owner : owners[index];
        }
    }
}
