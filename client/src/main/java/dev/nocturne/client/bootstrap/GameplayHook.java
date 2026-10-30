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
        default void playerTickPre(Object player) {
        }

        default void playerTickPost(Object player) {
        }

        default void motionPre(Object player) {
        }

        default void motionPost(Object player) {
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
    /** sendPosition 是否已直接织入；false 时由玩家 tick 前沿降级产生 MotionEvent。 */
    private static volatile boolean motionDirect;

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

    public static void playerTickPre(Object player) {
        try {
            Sink current = sink;
            if (current != null) {
                current.playerTickPre(player);
            }
        } catch (Throwable t) {
            failure("playerTickPre", t);
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

    public static boolean attack(Object player, Object target) {
        try {
            Sink current = sink;
            return current == null || current.attack(player, target);
        } catch (Throwable t) {
            failure("attack", t);
            return true;
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
