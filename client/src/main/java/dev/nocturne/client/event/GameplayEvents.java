package dev.nocturne.client.event;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.input.KeyMap;
import dev.nocturne.client.mapping.ClassType;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 把 bootstrap 钩子收到的 JDK 参数装配成客户端事件。 */
public final class GameplayEvents implements GameplayHook.Sink {

    private static final Set<String> FIRST_HITS = Collections.newSetFromMap(
            new ConcurrentHashMap<String, Boolean>());

    private final NocturneClient client;

    private GameplayEvents(NocturneClient client) {
        this.client = client;
    }

    public static void install(NocturneClient client) {
        GameplayHook.install(new GameplayEvents(client));
    }

    @Override
    public void playerTickPre(Object player) {
        if (!local(player)) {
            return;
        }
        hit("H1");
        client.events().post(PlayerTickEvent.Pre.INSTANCE);
        if (!GameplayHook.isMotionDirect()) {
            postMotion(player, true);
        }
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
        if (!active()) {
            return true;
        }
        hit("H7");
        return postKey(KeyMap.fromGlfw(key), action != 0);
    }

    @Override
    public boolean key(long window, int action, Object raw) {
        if (!active()) {
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
        hit(direction == PacketEvent.Direction.SEND ? "H3" : "H4");
        PacketEvent event = new PacketEvent(direction, packet);
        client.events().post(event);
        return event.cancelled ? null : event.packet;
    }

    private boolean postKey(int keyCode, boolean pressed) {
        KeyEvent event = new KeyEvent(keyCode, pressed);
        client.events().post(event);
        return !event.cancelled;
    }

    private boolean postMouse(int button, boolean pressed) {
        MouseButtonEvent event = new MouseButtonEvent(button, pressed);
        client.events().post(event);
        return !event.cancelled;
    }

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
