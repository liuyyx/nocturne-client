package dev.nocturne.client.event;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.input.KeyMap;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.runtime.InputBlock;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 把 bootstrap 钩子收到的 JDK 参数装配成客户端事件。 */
public final class GameplayEvents implements GameplayHook.Sink {

    private static final Set<String> FIRST_HITS = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private final NocturneClient client;
    private final ThreadLocal<MoveEvent> legacyMove = new ThreadLocal<MoveEvent>();

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
        return dev.nocturne.client.module.modules.render.NoRenderModule.shouldRender(entity)
                && dev.nocturne.client.module.modules.render.YsmModelModule.originalAllowed(entity);
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

    @Override
    public boolean xrayBlock(Object blockState, Object blockPos) {
        dev.nocturne.client.module.Module module = client.modules().get("Xray");
        return !(module instanceof dev.nocturne.client.module.modules.render.XrayModule)
                || !module.isEnabled()
                || ((dev.nocturne.client.module.modules.render.XrayModule) module)
                .shouldRender(blockState, blockPos);
    }

    @Override
    public void handTransform(Object pose, Object arm, float inverseHeight) {
        dev.nocturne.client.module.Module hand = client.modules().get("Hand View");
        if (hand instanceof dev.nocturne.client.module.modules.render.HandViewModule && hand.isEnabled()) {
            ((dev.nocturne.client.module.modules.render.HandViewModule) hand).apply(pose, arm, inverseHeight);
        }
        dev.nocturne.client.module.Module totem = client.modules().get("Totem Animation");
        if (totem instanceof dev.nocturne.client.module.modules.render.TotemAnimationModule && totem.isEnabled()) {
            ((dev.nocturne.client.module.modules.render.TotemAnimationModule) totem).apply(pose);
        }
    }

    @Override
    public float cameraZoom(float distance) {
        dev.nocturne.client.module.Module module = client.modules().get("Camera Clip");
        return module instanceof dev.nocturne.client.module.modules.render.CameraClipModule && module.isEnabled()
                ? ((dev.nocturne.client.module.modules.render.CameraClipModule) module).zoom(distance) : distance;
    }

    @Override
    public float timerScale(float delta) {
        dev.nocturne.client.module.Module module = client.modules().get("Timer");
        return module instanceof dev.nocturne.client.module.modules.misc.TimerModule && module.isEnabled()
                ? delta * ((dev.nocturne.client.module.modules.misc.TimerModule) module).speed() : delta;
    }

    @Override
    public boolean noFog() {
        dev.nocturne.client.module.Module module = client.modules().get("NoFog");
        return (module == null || !module.isEnabled())
                && !dev.nocturne.client.module.modules.render.NoRenderModule.fog();
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
        if (active()) {
            client.events().post(new BlockEvent(BlockEvent.Phase.STOP, null, null));
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
        return !(button instanceof Number)
                || postMouse(((Number) button).intValue(), action != 0);
    }

    @Override
    public Object packetSend(Object packet) {
        return packet(packet, PacketEvent.Direction.SEND);
    }

    @Override
    public Object packetReceive(Object packet) {
        return packet(packet, PacketEvent.Direction.RECEIVE);
    }

    private Object packet(Object packet, PacketEvent.Direction direction) {
        if (!active()) {
            return packet;
        }
        if (client.packets().shouldBypass(packet)) {
            return packet;
        }
        if (client.packets().capture(direction, packet)) {
            return null;
        }
        hit(direction == PacketEvent.Direction.SEND ? "H3" : "H4");
        PacketEvent event = new PacketEvent(direction, packet);
        client.events().post(event);
        return event.cancelled ? null : event.packet;
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
