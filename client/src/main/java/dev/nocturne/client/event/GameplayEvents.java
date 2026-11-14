package dev.nocturne.client.event;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.control.PacketController;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.input.KeyMap;
import dev.nocturne.client.input.MouseButtons;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.runtime.InputBlock;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 把 bootstrap 钩子收到的 JDK 参数装配成客户端事件。 */
public final class GameplayEvents implements GameplayHook.Sink {

    private static final Set<String> FIRST_HITS = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private final NocturneClient client;
    private final ThreadLocal<MoveEvent> legacyMove = new ThreadLocal<MoveEvent>();
    /**
     * 收到、但事件尚未在主线程投递的包。
     *
     * <p>{@code channelRead0} 在 Netty 事件循环上执行；直接在它里面 post 会让订阅者在 IO 线程
     * 改写模块/游戏状态（非 volatile 字段、HashSet、setEnabled）。这里只入队，由
     * {@link #drainReceive()} 在客户端主线程统一投递。
     */
    private final ConcurrentLinkedQueue<PendingReceive> pendingReceives =
            new ConcurrentLinkedQueue<PendingReceive>();

    private GameplayEvents(NocturneClient client) {
        this.client = client;
    }

    public static GameplayEvents install(NocturneClient client) {
        GameplayEvents events = new GameplayEvents(client);
        GameplayHook.install(events);
        return events;
    }

    @Override
    public boolean playerTickPre(Object player) {
        if (!active()) {
            return true;
        }
        if (!local(player)) {
            return true;
        }
        hit("H1");
        PlayerTickEvent.Pre event = new PlayerTickEvent.Pre();
        client.events().post(event);
        if (!GameplayHook.isMotionDirect() && !event.cancelled) {
            postMotion(player, true);
        }
        return !event.cancelled;
    }

    @Override
    public void playerTickPost(Object player) {
        if (!local(player)) {
            return;
        }
        client.events().post(PlayerTickEvent.Post.INSTANCE);
    }

    @Override
    public void motionPre(Object player) {
        if (active()) {
            GameplayHook.markMotionDirect();
            hit("H2");
            postMotion(player, true);
        }
    }

    @Override
    public void motionPost(Object player) {
        if (active()) {
            postMotion(player, false);
        }
    }

    @Override
    public void inputTick(Object input) {
        if (!active()) {
            return;
        }
        MovementInputEvent event = MovementInputEvent.read(client.gameBridge(), input);
        if (event == null) {
            return;
        }
        hit("H9");
        client.events().post(event);
        event.apply();
    }

    @Override
    public boolean screenOpen(Object screen) {
        if (!active()) {
            return true;
        }
        hit("H10");
        ScreenEvent event = new ScreenEvent(screen);
        client.events().post(event);
        return !event.cancelled;
    }

    @Override
    public void entityJoin(Object entity) {
        entityJoin(-1, entity);
    }

    @Override
    public void entityJoin(int id, Object entity) {
        if (!active()) {
            return;
        }
        hit("H11");
        client.events().post(new EntityJoinEvent(id, entity));
    }

    @Override
    public boolean entityRenderGate(Object entity) {
        if (!active() || entity == null) {
            return true;
        }
        dev.nocturne.client.module.Module module = client.modules().get("No Render");
        if (!(module instanceof dev.nocturne.client.module.modules.render.NoRenderModule)
                || !module.isEnabled()) {
            return true;
        }
        try {
            return ((dev.nocturne.client.module.modules.render.NoRenderModule) module)
                    .entityRenderGate(entity);
        } catch (Throwable t) {
            warnReceive("entityRenderGate", "entity render gate failed; allowing original render: " + t);
            return true;
        }
    }

    /** {@code EntityRenderDispatcher#shouldRender(Entity,Frustum,DDD)Z} 的两参形态：Frustum 只占位。 */
    @Override
    public boolean entityRenderGate(Object entity, Object frustum) {
        return entityRenderGate(entity);
    }

    @Override
    public void entityRender(Object entity, boolean post) {
        if (active()) {
            client.events().post(new EntityRenderEvent(entity, post));
        }
    }

    /**
     * 世界空间 3D 绘制：交给 {@code World3DRenderer}。
     *
     * <p>这里不再判 {@link #active()}——3D 通道本身就是渲染回调，且 renderer 内部已有 panic /
     * 相机 / 后端三重守卫；把判断放两处只会让「为什么没画」更难排查。
     */
    @Override
    public void worldRender3D(float partialTicks) {
        dev.nocturne.client.render3d.World3DRenderer renderer = client.world3d();
        if (renderer != null) {
            renderer.render(partialTicks);
        }
    }

    /** 世界渲染头部：捕获世界相机矩阵，供尾部绘制复用。 */
    @Override
    public void worldRender3DCapture(float partialTicks) {
        dev.nocturne.client.render3d.World3DRenderer renderer = client.world3d();
        if (renderer != null) {
            renderer.captureMatrices();
        }
    }

    @Override
    public boolean xrayBlock(Object blockState, Object blockPos) {
        if (!active()) return true;
        dev.nocturne.client.module.Module module = client.modules().get("Xray");
        if (!(module instanceof dev.nocturne.client.module.modules.render.XrayModule)
                || !module.isEnabled()) return true;
        try {
            return ((dev.nocturne.client.module.modules.render.XrayModule) module)
                    .shouldRender(blockState, blockPos);
        } catch (Throwable t) {
            warnReceive("xrayBlock", "xray block gate failed; allowing original block: " + t);
            return true;
        }
    }

    @Override
    public void handTransform(Object pose, Object arm, float inverseHeight) {
        if (!active() || pose == null) {
            return;
        }
        dev.nocturne.client.module.Module module = client.modules().get("Hand View");
        if (!(module instanceof dev.nocturne.client.module.modules.render.HandViewModule)
                || !module.isEnabled()) {
            return;
        }
        try {
            ((dev.nocturne.client.module.modules.render.HandViewModule) module)
                    .apply(pose, arm, inverseHeight);
        } catch (Throwable t) {
            warnReceive("handTransform", "hand transform failed; preserving original pose: " + t);
        }
    }

    @Override
    public float cameraZoom(float distance) {
        if (!active()) {
            return distance;
        }
        dev.nocturne.client.module.Module module = client.modules().get("Camera Clip");
        if (!(module instanceof dev.nocturne.client.module.modules.render.CameraClipModule)
                || !module.isEnabled()) {
            return distance;
        }
        try {
            return ((dev.nocturne.client.module.modules.render.CameraClipModule) module)
                    .zoom(distance);
        } catch (Throwable t) {
            warnReceive("cameraZoom", "camera zoom failed; preserving original distance: " + t);
            return distance;
        }
    }

    @Override
    public void cameraAligned(Object camera) {
        if (!active() || camera == null) {
            return;
        }
        for (dev.nocturne.client.module.Module module : client.modules().all()) {
            if (!module.isEnabled()
                    || !(module instanceof dev.nocturne.client.render.CameraOverride)) {
                continue;
            }
            try {
                ((dev.nocturne.client.render.CameraOverride) module).applyCamera(camera);
            } catch (Throwable t) {
                warnReceive("cameraAligned:" + module.name(),
                        "camera aligned callback failed for module '" + module.name() + "': " + t);
            }
        }
    }

    @Override
    public float timerScale(float delta) {
        return delta;
    }

    @Override
    public boolean noFog() {
        if (!active()) {
            return true;
        }
        // 真值语义：true=允许雾。任一模块要求禁雾时返回 false，保持与原 setupFog 门一致。
        try {
            dev.nocturne.client.module.Module noRender = client.modules().get("No Render");
            if (noRender instanceof dev.nocturne.client.module.modules.render.NoRenderModule
                    && noRender.isEnabled()
                    && ((dev.nocturne.client.module.modules.render.NoRenderModule) noRender).noFog()) {
                return false;
            }
        } catch (Throwable t) {
            warnReceive("noFog:NoRender", "fog gate (No Render) failed; allowing fog: " + t);
        }
        try {
            dev.nocturne.client.module.Module tweaks = client.modules().get("World Tweaks");
            if (tweaks instanceof dev.nocturne.client.module.modules.render.WorldTweaksModule
                    && tweaks.isEnabled()
                    && ((dev.nocturne.client.module.modules.render.WorldTweaksModule) tweaks).noFog()) {
                return false;
            }
        } catch (Throwable t) {
            warnReceive("noFog:WorldTweaks", "fog gate (World Tweaks) failed; allowing fog: " + t);
        }
        return true;
    }

    @Override
    public boolean noHurtCamera() {
        dev.nocturne.client.module.Module module = client.modules().get("NoHurtCamera");
        return module == null || !module.isEnabled();
    }

    @Override
    public boolean destroyStart(Object blockPos, Object direction) {
        return destroy(BlockEvent.Phase.START, blockPos, direction);
    }

    @Override
    public boolean destroyContinue(Object blockPos, Object direction) {
        return destroy(BlockEvent.Phase.CONTINUE, blockPos, direction);
    }

    private boolean destroy(BlockEvent.Phase phase, Object blockPos, Object direction) {
        if (!active()) {
            return true;
        }
        hit("H12");
        BlockEvent event = new BlockEvent(phase, blockPos, direction);
        client.events().post(event);
        return !event.cancelled;
    }

    @Override
    public void destroyStop() {
        if (active()) client.events().post(new BlockEvent(BlockEvent.Phase.STOP, null, null));
    }

    @Override
    public boolean destroyBlock(Object blockPos) {
        if (!active()) return true;
        dev.nocturne.client.module.Module module = client.modules().get("No Ghost Blocks");
        if (!(module instanceof dev.nocturne.client.module.modules.render.NoGhostBlocksModule)
                || !module.isEnabled()) return true;
        try {
            return !((dev.nocturne.client.module.modules.render.NoGhostBlocksModule) module)
                    .shouldSuppressDestroy();
        } catch (Throwable t) {
            warnReceive("destroyBlock", "local block destruction gate failed; preserving original behaviour: " + t);
            return true;
        }
    }

    @Override
    public Object moveVec(Object entity, Object motion) {
        if (motion == null || !local(entity)) {
            return motion;
        }
        GameBridge bridge = client.gameBridge();
        Number x = number(bridge.readField(motion, ClassType.VEC3, "x"));
        Number y = number(bridge.readField(motion, ClassType.VEC3, "y"));
        Number z = number(bridge.readField(motion, ClassType.VEC3, "z"));
        if (x == null || y == null || z == null) {
            return motion;
        }
        MoveEvent event = new MoveEvent(x.doubleValue(), y.doubleValue(), z.doubleValue());
        hit("H13");
        client.events().post(event);
        if (!event.changed() || !event.finite()) {
            return motion;
        }
        Object replacement = bridge.construct(ClassType.VEC3, event.x, event.y, event.z);
        return replacement == null ? motion : replacement;
    }

    @Override
    public void moveLegacyCapture(Object entity, double x, double y, double z) {
        if (local(entity)) {
            legacyMove.set(new MoveEvent(x, y, z));
        } else {
            legacyMove.remove();
        }
    }

    @Override
    public void moveLegacyDispatch(Object entity) {
        MoveEvent event = legacyMove.get();
        if (event != null) {
            hit("H13");
            client.events().post(event);
        }
    }

    @Override
    public double moveLegacyX(Object entity, double value) {
        return legacyMoveValue(value, 0);
    }

    @Override
    public double moveLegacyY(Object entity, double value) {
        return legacyMoveValue(value, 1);
    }

    @Override
    public double moveLegacyZ(Object entity, double value) {
        return legacyMoveValue(value, 2);
    }

    private double legacyMoveValue(double original, int axis) {
        MoveEvent event = legacyMove.get();
        if (event == null || !event.finite()) {
            legacyMove.remove();
            return original;
        }
        double value = axis == 0 ? event.x : axis == 1 ? event.y : event.z;
        if (axis == 2) {
            legacyMove.remove();
        }
        return value;
    }

    @Override
    public boolean attack(Object player, Object target) {
        if (!active()) {
            return true;
        }
        hit("H5");
        AttackEvent event = new AttackEvent(target, false);
        client.events().post(event);
        return !event.cancelled;
    }

    @Override
    public void attackPost(Object player, Object target) {
        if (active()) {
            client.events().post(new AttackEvent(target, true));
        }
    }

    @Override
    public boolean leftClick() {
        if (!active()) {
            return true;
        }
        hit("H6");
        MouseButtonEvent event = new MouseButtonEvent(0, true);
        client.events().post(event);
        return !event.cancelled;
    }

    @Override
    public boolean useItem() {
        if (!active()) {
            return true;
        }
        hit("H6");
        MouseButtonEvent mouse = new MouseButtonEvent(1, true);
        client.events().post(mouse);
        if (mouse.cancelled) {
            return false;
        }
        UseItemEvent use = new UseItemEvent(false);
        client.events().post(use);
        return !use.cancelled;
    }

    @Override
    public boolean key(long window, int key, int scancode, int action, int modifiers) {
        if (!active() || action == 2 || action < 0) {
            return true;
        }
        hit("H7");
        return postKey(KeyMap.fromGlfw(key), action != 0);
    }

    @Override
    public boolean key(long window, int action, Object raw) {
        if (!active() || action == 2 || action < 0) {
            return true;
        }
        hit("H7");
        GameBridge bridge = client.gameBridge();
        Object key = bridge == null ? null : bridge.callMapped(raw, ClassType.KEY_EVENT, "key");
        return !(key instanceof Number)
                || postKey(KeyMap.fromSdl(((Number) key).intValue()), action != 0);
    }

    @Override
    public boolean mouse(long window, int button, int action, int modifiers) {
        if (!active()) {
            return true;
        }
        hit("H8");
        return postMouse(button, action != 0);
    }

    @Override
    public boolean mouse(long window, Object raw, int action) {
        if (!active()) {
            return true;
        }
        hit("H8");
        GameBridge bridge = client.gameBridge();
        Object button = bridge == null ? null
                : bridge.callMapped(raw, ClassType.MOUSE_BUTTON_INFO, "button");
        // 26.3 的 onButton 来自 SDL：MouseButtonInfo 是 1=左/2=中/3=右，必须先归一成契约
        // 0=左/1=右/2=中，否则右键模块会被左键触发（RightClicker 真机复现）。
        return !(button instanceof Number)
                || postMouse(MouseButtons.fromSdl(((Number) button).intValue()), action != 0);
    }

    @Override
    public Object packetSend(Object packet) {
        if (!active() || packet == null) {
            return packet;
        }
        if (client.packets().shouldBypass(packet)) {
            return packet;
        }
        hit("H3");
        // 先投递事件（取消/替换优先于入队），未取消且闸门仍开启才压住原调用。
        PacketEvent event = new PacketEvent(PacketEvent.Direction.SEND, packet);
        client.events().post(event);
        if (event.cancelled || event.packet == null) {
            return null;
        }
        if (client.packets().capture(PacketEvent.Direction.SEND, event.packet)) {
            return null;
        }
        return event.packet;
    }

    /**
     * 收包入口（Netty 线程）。
     *
     * <p>这里绝不 post 事件，也绝不执行模块逻辑：包只登记进队列并返回 null，原版处理与
     * PacketEvent 统一由 {@link #drainReceive()} 在客户端主线程完成。这样订阅者（Velocity、
     * NoRotate、ChestAura…）永远不会在 IO 线程改写模块/游戏状态，同时 cancel/setPacket 仍然
     * 发生在原版处理之前。
     *
     * <p>同步丢弃走 {@link PacketController#filterReceive}（纯判断，见其契约）。进入世界前的
     * 登录/握手包没有本地连接可绑定，直接放行给原版——推迟它们会让登录阶段失去 handler。
     */
    @Override
    public Object packetReceive(Object packet) {
        if (!active() || packet == null) {
            return packet;
        }
        PacketController packets = client.packets();
        if (packets == null || packets.shouldBypass(packet)) {
            return packet;
        }
        if (packets.filterReceive(packet)) {
            hit("H4");
            return null;
        }
        Object connection = packets.connection();
        if (connection == null || !isClientbound(packet, connection)) {
            // 未进世界（登录/握手/断开）或这是集成服务端连接上的 serverbound 包：交还原版。
            // 同一个 Connection 类在集成服务端也挂了这个钩子，按 handle 的监听器类型区分方向。
            return packet;
        }
        pendingReceives.add(new PendingReceive(packet, connection, packets.epoch()));
        return null;
    }

    /**
     * 包是不是客户端收包方向。
     *
     * <p>{@code channelRead0} 钩子挂在 {@code Connection} 上，单机时集成服务端的同一个类也会
     * 触发；若把 serverbound 包当成收包推迟，就会用客户端 handler 重放它。这里按包自己的
     * {@code handle(Listener)} 形参判定：只有客户端连接实例能作为参数时才视为 clientbound。
     *
     * <p>形参类型按包类缓存；方向判定与映射表无关（26.3 之前是 {@code INetHandlerPlayClient}，
     * 之后是 {@code ClientGamePacketListener}，{@code isInstance} 都成立）。
     */
    private static boolean isClientbound(Object packet, Object connection) {
        Class<?> handler = HANDLE_PARAMETERS.get(packet.getClass());
        if (handler == null) {
            handler = Object.class;
            for (Method method : packet.getClass().getMethods()) {
                if (!"handle".equals(method.getName()) || method.getParameterTypes().length != 1) {
                    continue;
                }
                Class<?> parameter = method.getParameterTypes()[0];
                // 存在桥方法（handle(INetHandler)）：取更具体的那个形参。
                if (handler == Object.class || handler.isAssignableFrom(parameter)) {
                    handler = parameter;
                }
            }
            HANDLE_PARAMETERS.put(packet.getClass(), handler);
        }
        return handler != Object.class && handler.isInstance(connection);
    }

    /** 包类 → handle 的最具体监听器形参；见 {@link #isClientbound}。 */
    private static final ConcurrentHashMap<Class<?>, Class<?>> HANDLE_PARAMETERS =
            new ConcurrentHashMap<Class<?>, Class<?>>();

    /**
     * 在客户端主线程投递积压的 RECEIVE 事件并完成原版处理；由 {@code ModuleTicker} 每帧调用。
     *
     * <p>顺序契约：先 post PacketEvent（cancel/setPacket 生效），未取消且闸门仍开启时进入包
     * 队列等待放行；否则立即在主线程调用原版 {@code Packet.handle}。被 hold 的包在 release 时
     * 由 PacketController 重放，不会重复投递事件。
     */
    public void drainReceive() {
        PendingReceive pending;
        while ((pending = pendingReceives.poll()) != null) {
            dispatchReceive(pending);
        }
    }

    /** panic：客户端不再驱动帧，丢弃尚未投递的收包（世界切换不得走这里——那条路径要按序处理）。 */
    public void discardPendingReceive() {
        pendingReceives.clear();
    }

    private void dispatchReceive(PendingReceive pending) {
        PacketController packets = client.packets();
        if (packets == null) {
            return;
        }
        if (pending.epoch != packets.epoch()) {
            // 世界/连接已切换：与 PacketController.reset 相同的丢弃语义，旧包不得重放。
            return;
        }
        Object connection = packets.connection();
        if (pending.connection != null && connection != null && connection != pending.connection) {
            return;
        }
        hit("H4");
        PacketEvent event = new PacketEvent(PacketEvent.Direction.RECEIVE, pending.packet);
        client.events().post(event);
        if (event.cancelled || event.packet == null) {
            return;
        }
        if (packets.capture(PacketEvent.Direction.RECEIVE, event.packet)) {
            // 闸门开启：放行时由 PacketController 走原版 handle，事件已经投递过，不再重复。
            return;
        }
        if (!packets.handleReceived(event.packet, pending.connection)) {
            warnReceive("handle", "deferred received packet could not be replayed");
        }
    }

    private static final Set<String> RECEIVE_WARNINGS = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private static void warnReceive(String key, String message) {
        if (RECEIVE_WARNINGS.add(key)) {
            System.out.println("[nocturne] " + message);
        }
    }

    /** 一次延迟投递的收包任务。 */
    private static final class PendingReceive {
        private final Object packet;
        private final Object connection;
        private final long epoch;

        private PendingReceive(Object packet, Object connection, long epoch) {
            this.packet = packet;
            this.connection = connection;
            this.epoch = epoch;
        }
    }

    private boolean postKey(int keyCode, boolean pressed) {
        KeyEvent event = new KeyEvent(keyCode, pressed);
        client.events().post(event);
        // 自绘 GUI 开着时不能把按键交还游戏：游戏侧入口的返回值是"是否继续处理"。
        if (InputBlock.shouldBlock()) {
            return false;
        }
        return !event.cancelled;
    }

    private boolean postMouse(int button, boolean pressed) {
        MouseButtonEvent event = new MouseButtonEvent(button, pressed);
        client.events().post(event);
        // 同上：交还点击会让游戏顺带挥手臂/挖方块——玩家看到的是"点模块时手臂在挥"。
        // 模块仍然照常收到 MouseButtonEvent，只有游戏自己那份被拦掉。
        if (InputBlock.shouldBlock()) {
            return false;
        }
        return !event.cancelled;
    }

    /** 位置诊断开关：{@code -Dnocturne.debug.motion} 时打印每 tick 的位移与写回结果。 */
    private static final boolean MOTION_DEBUG =
            System.getProperty("nocturne.debug.motion") != null;
    /** 上一 tick 记录的玩家位置（诊断用）。 */
    private double debugLastX = Double.NaN;
    private double debugLastY = Double.NaN;
    private double debugLastZ = Double.NaN;
    /** 诊断日志限流：每秒最多一条。 */
    private long debugMotionLoggedAt;

    /** 读取完整 Motion 状态；缺任何必需成员时不投递，更不把零值写回玩家。 */
    private void postMotion(Object player, boolean pre) {
        GameBridge bridge = client.gameBridge();
        if (bridge == null || player == null) {
            return;
        }
        Object position = bridge.callMapped(player, ClassType.ENTITY, "position");
        Number x = number(bridge.readField(position, ClassType.VEC3, "x"));
        Number y = number(bridge.readField(position, ClassType.VEC3, "y"));
        Number z = number(bridge.readField(position, ClassType.VEC3, "z"));
        Number yaw = number(bridge.readField(player, ClassType.ENTITY, "yRot"));
        Number pitch = number(bridge.readField(player, ClassType.ENTITY, "xRot"));
        Object ground = bridge.readField(player, ClassType.ENTITY, "onGround");
        if (x == null || y == null || z == null || yaw == null || pitch == null
                || !(ground instanceof Boolean)) {
            return;
        }
        MotionEvent event = new MotionEvent(pre, x.doubleValue(), y.doubleValue(), z.doubleValue(),
                yaw.floatValue(), pitch.floatValue(), (Boolean) ground);
        client.events().post(event);
        if (!pre) {
            return;
        }
        if (MOTION_DEBUG) {
            // 一 tick 位移超过 5 格（正常走路 < 0.5 格）几乎只可能是位置被谁改坏了：
            // 玩家报「开覆盖层后人物飞速移动」时，这行直接区分「位置异常」与「只是掉帧看起来快」。
            double dx = x.doubleValue() - debugLastX;
            double dy = y.doubleValue() - debugLastY;
            double dz = z.doubleValue() - debugLastZ;
            double step = Double.isNaN(debugLastX) ? 0d : Math.sqrt(dx * dx + dy * dy + dz * dz);
            long now = System.currentTimeMillis();
            if ((step > 5d || Math.abs(event.x - x.doubleValue()) > 0.5d) && now - debugMotionLoggedAt > 1000L) {
                debugMotionLoggedAt = now;
                System.out.println("[nocturne] motion anomaly: step=" + step
                        + " at=" + x.doubleValue() + "," + y.doubleValue() + "," + z.doubleValue()
                        + " post=" + event.x + "," + event.y + "," + event.z
                        + " yaw=" + yaw.floatValue() + "->" + event.yaw);
            }
            debugLastX = x.doubleValue();
            debugLastY = y.doubleValue();
            debugLastZ = z.doubleValue();
        }
        if (finite(event.x) && finite(event.y) && finite(event.z)) {
            bridge.callMapped(player, ClassType.ENTITY, "setPos", event.x, event.y, event.z);
        }
        if (finite(event.yaw)) {
            bridge.writeField(player, ClassType.ENTITY, "yRot", event.yaw);
        }
        if (finite(event.pitch)) {
            bridge.writeField(player, ClassType.ENTITY, "xRot", event.pitch);
        }
        bridge.writeField(player, ClassType.ENTITY, "onGround", event.onGround);
    }

    private boolean local(Object player) {
        if (!active() || player == null) {
            return false;
        }
        GameBridge bridge = client.gameBridge();
        return bridge != null && player == bridge.player();
    }

    private boolean active() {
        return !client.isPanicked();
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static void hit(String hook) {
        if (FIRST_HITS.add(hook)) {
            System.out.println("[nocturne] hook " + hook + " hit");
        }
    }
}
