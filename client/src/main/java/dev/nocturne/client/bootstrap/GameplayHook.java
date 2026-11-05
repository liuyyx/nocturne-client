package dev.nocturne.client.bootstrap;

/**
 * 游戏事件钩子汇。
 *
 * <p>本类会被放入 bootstrap 层，只使用 JDK 类型。游戏字节码在这里调用静态入口；
 * 客户端启动时安装一个 {@link Sink}，由它构造并投递具体事件。
 */
public final class GameplayHook {

    /** 客户端事件装配面；所有默认实现都必须是无副作用的放行行为。 */
    public interface Sink {
        default boolean playerTickPre(Object player) {
            return true;
        }

        default void playerTickPost(Object player) {
        }

        default void motionPre(Object player) {
        }

        default void motionPost(Object player) {
        }

        default void inputTick(Object input) {
        }

        default boolean screenOpen(Object screen) {
            return true;
        }

        default void entityJoin(Object entity) {
        }

        default void entityJoin(int id, Object entity) {
        }

        default void entityRender(Object entity, boolean post) {
        }

        default boolean xrayBlock(Object blockState, Object blockPos) {
            return true;
        }

        default float timerScale(float delta) {
            return delta;
        }

        default boolean noFog() {
            return true;
        }

        default boolean noHurtCamera() {
            return true;
        }

        default boolean destroyStart(Object blockPos, Object direction) {
            return true;
        }

        default boolean destroyContinue(Object blockPos, Object direction) {
            return true;
        }

        default void destroyStop() {
        }

        default Object moveVec(Object entity, Object motion) {
            return motion;
        }

        default void moveLegacyCapture(Object entity, double x, double y, double z) {
        }

        default void moveLegacyDispatch(Object entity) {
        }

        default double moveLegacyX(Object entity, double value) {
            return value;
        }

        default double moveLegacyY(Object entity, double value) {
            return value;
        }

        default double moveLegacyZ(Object entity, double value) {
            return value;
        }

        default boolean attack(Object player, Object target) {
            return true;
        }

        default void attackPost(Object player, Object target) {
        }

        default boolean leftClick() {
            return true;
        }

        default boolean useItem() {
            return true;
        }

        default boolean key(long window, int key, int scancode, int action, int modifiers) {
            return true;
        }

        default boolean key(long window, int action, Object event) {
            return true;
        }

        default boolean mouse(long window, int button, int action, int modifiers) {
            return true;
        }

        default boolean mouse(long window, Object event, int action) {
            return true;
        }

        default Object packetSend(Object packet) {
            return packet;
        }

        default Object packetReceive(Object packet) {
            return packet;
        }
    }

    private static volatile Sink sink;
    private static final ThreadLocal<Object[]> ATTACK_CONTEXT = new ThreadLocal<Object[]>();
    /** sendPosition 是否已直接织入；false 时由玩家 tick 前沿降级产生 MotionEvent。 */
    private static volatile boolean motionDirect;
    /** 首个 Motion 事件是否已记录；用于区分"钩子未织入"与"事件未投递"。 */
    private static volatile boolean motionLogged;
    /** 首个 tick 事件是否已记录。 */
    private static volatile boolean tickLogged;

    private GameplayHook() {
    }

    public static void install(Sink replacement) {
        sink = replacement;
    }

    public static void markMotionDirect() {
        motionDirect = true;
    }

    public static boolean isMotionDirect() {
        return motionDirect;
    }

    public static boolean playerTickPre(Object player) {
        try {
            if (!tickLogged) {
                tickLogged = true;
                System.out.println("[nocturne] gameplay hook first tick event; sink="
                        + (sink != null));
            }
            Sink current = sink;
            return current == null || current.playerTickPre(player);
        } catch (Throwable t) {
            failure("playerTickPre", t);
            return true;
        }
    }

    public static void playerTickPost(Object player) {
        try {
            Sink current = sink;
            if (current != null) {
                current.playerTickPost(player);
            }
        } catch (Throwable t) {
            failure("playerTickPost", t);
        }
    }

    public static void motionPre(Object player) {
        try {
            if (!motionLogged) {
                motionLogged = true;
                System.out.println("[nocturne] gameplay hook first motion event; sink="
                        + (sink != null));
            }
            Sink current = sink;
            if (current != null) {
                current.motionPre(player);
            }
        } catch (Throwable t) {
            failure("motionPre", t);
        }
    }

    public static void motionPost(Object player) {
        try {
            Sink current = sink;
            if (current != null) {
                current.motionPost(player);
            }
        } catch (Throwable t) {
            failure("motionPost", t);
        }
    }

    public static void inputTick(Object input) {
        try {
            Sink current = sink;
            if (current != null) {
                current.inputTick(input);
            }
        } catch (Throwable t) {
            failure("inputTick", t);
        }
    }

    public static boolean screenOpen(Object screen) {
        try {
            Sink current = sink;
            return current == null || current.screenOpen(screen);
        } catch (Throwable t) {
            failure("screenOpen", t);
            return true;
        }
    }

    public static boolean noFog() {
        try {
            Sink current = sink;
            return current == null || current.noFog();
        } catch (Throwable t) {
            failure("noFog", t);
            return true;
        }
    }

    public static boolean noHurtCamera() {
        try {
            Sink current = sink;
            return current == null || current.noHurtCamera();
        } catch (Throwable t) {
            failure("noHurtCamera", t);
            return true;
        }
    }

    public static float timerScale(float delta) {
        try {
            Sink current = sink;
            return current == null ? delta : current.timerScale(delta);
        } catch (Throwable t) {
            failure("timerScale", t);
            return delta;
        }
    }

    public static boolean xrayBlock(Object blockState, Object blockPos) {
        try {
            Sink current = sink;
            return current == null || current.xrayBlock(blockState, blockPos);
        } catch (Throwable t) {
            failure("xrayBlock", t);
            return true;
        }
    }

    public static void chamsPre(Object entity) {
        entityRender(entity, false);
    }

    public static void chamsPost(Object entity) {
        entityRender(entity, true);
    }

    private static void entityRender(Object entity, boolean post) {
        try {
            Sink current = sink;
            if (current != null) {
                current.entityRender(entity, post);
            }
        } catch (Throwable t) {
            failure("entityRender", t);
        }
    }

    public static void entityJoin(Object entity) {
        entityJoin(-1, entity);
    }

    public static void entityJoin(int id, Object entity) {
        try {
            Sink current = sink;
            if (current != null) {
                current.entityJoin(id, entity);
            }
        } catch (Throwable t) {
            failure("entityJoin", t);
        }
    }

    public static boolean destroyStart(Object blockPos, Object direction) {
        return destroy(true, blockPos, direction);
    }

    public static boolean destroyContinue(Object blockPos, Object direction) {
        return destroy(false, blockPos, direction);
    }

    private static boolean destroy(boolean start, Object blockPos, Object direction) {
        try {
            Sink current = sink;
            return current == null || (start ? current.destroyStart(blockPos, direction)
                    : current.destroyContinue(blockPos, direction));
        } catch (Throwable t) {
            failure(start ? "destroyStart" : "destroyContinue", t);
            return true;
        }
    }

    public static void destroyStop() {
        try {
            Sink current = sink;
            if (current != null) {
                current.destroyStop();
            }
        } catch (Throwable t) {
            failure("destroyStop", t);
        }
    }

    public static Object moveVec(Object entity, Object motion) {
        try {
            Sink current = sink;
            Object replacement = current == null ? motion : current.moveVec(entity, motion);
            return replacement == null ? motion : replacement;
        } catch (Throwable t) {
            failure("moveVec", t);
            return motion;
        }
    }

    public static void moveLegacyCapture(Object entity, double x, double y, double z) {
        try {
            Sink current = sink;
            if (current != null) {
                current.moveLegacyCapture(entity, x, y, z);
            }
        } catch (Throwable t) {
            failure("moveLegacyCapture", t);
        }
    }

    public static void moveLegacyDispatch(Object entity) {
        try {
            Sink current = sink;
            if (current != null) {
                current.moveLegacyDispatch(entity);
            }
        } catch (Throwable t) {
            failure("moveLegacyDispatch", t);
        }
    }

    public static double moveLegacyX(Object entity, double value) {
        return moveLegacyValue(entity, value, 0);
    }

    public static double moveLegacyY(Object entity, double value) {
        return moveLegacyValue(entity, value, 1);
    }

    public static double moveLegacyZ(Object entity, double value) {
        return moveLegacyValue(entity, value, 2);
    }

    private static double moveLegacyValue(Object entity, double value, int axis) {
        try {
            Sink current = sink;
            if (current != null) {
                return axis == 0 ? current.moveLegacyX(entity, value)
                        : axis == 1 ? current.moveLegacyY(entity, value)
                        : current.moveLegacyZ(entity, value);
            }
        } catch (Throwable t) {
            failure("moveLegacyValue", t);
        }
        return value;
    }

    public static boolean attack(Object player, Object target) {
        try {
            Sink current = sink;
            boolean allowed = current == null || current.attack(player, target);
            if (!allowed) {
                ATTACK_CONTEXT.remove();
            }
            return allowed;
        } catch (Throwable t) {
            failure("attack", t);
            return true;
        }
    }

    /** attack 的头部织入点；实际形参在此仍必定位于局部变量槽。 */
    public static void attackCapture(Object player, Object target) {
        ATTACK_CONTEXT.set(new Object[]{player, target});
    }

    /** attack 的返回点织入点；不读取可能已经失效的形参槽。 */
    public static void attackPost() {
        Object[] context = ATTACK_CONTEXT.get();
        ATTACK_CONTEXT.remove();
        if (context != null) {
            attackPost(context[0], context[1]);
        }
    }

    public static void attackPost(Object player, Object target) {
        try {
            Sink current = sink;
            if (current != null) {
                current.attackPost(player, target);
            }
        } catch (Throwable t) {
            failure("attackPost", t);
        }
    }

    public static boolean leftClick() {
        try {
            Sink current = sink;
            return current == null || current.leftClick();
        } catch (Throwable t) {
            failure("leftClick", t);
            return true;
        }
    }

    public static boolean useItem() {
        try {
            Sink current = sink;
            return current == null || current.useItem();
        } catch (Throwable t) {
            failure("useItem", t);
            return true;
        }
    }

    public static boolean key(long window, int key, int scancode, int action, int modifiers) {
        try {
            Sink current = sink;
            return current == null || current.key(window, key, scancode, action, modifiers);
        } catch (Throwable t) {
            failure("key", t);
            return true;
        }
    }

    public static boolean key(long window, int action, Object event) {
        try {
            Sink current = sink;
            return current == null || current.key(window, action, event);
        } catch (Throwable t) {
            failure("key", t);
            return true;
        }
    }

    public static boolean mouse(long window, int button, int action, int modifiers) {
        try {
            Sink current = sink;
            return current == null || current.mouse(window, button, action, modifiers);
        } catch (Throwable t) {
            failure("mouse", t);
            return true;
        }
    }

    public static boolean mouse(long window, Object event, int action) {
        try {
            Sink current = sink;
            return current == null || current.mouse(window, event, action);
        } catch (Throwable t) {
            failure("mouse", t);
            return true;
        }
    }

    public static Object packetSend(Object packet) {
        try {
            Sink current = sink;
            return current == null ? packet : current.packetSend(packet);
        } catch (Throwable t) {
            failure("packetSend", t);
            return packet;
        }
    }

    public static Object packetReceive(Object packet) {
        try {
            Sink current = sink;
            return current == null ? packet : current.packetReceive(packet);
        } catch (Throwable t) {
            failure("packetReceive", t);
            return packet;
        }
    }

    /** 钩子失败必须放行原游戏行为；日志按五秒窗口限流。 */
    private static synchronized void failure(String hook, Throwable error) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogAt < 5000L) {
            return;
        }
        lastFailureLogAt = now;
        System.out.println("[nocturne] gameplay hook " + hook + " failed: " + error);
    }

    private static long lastFailureLogAt;
}
