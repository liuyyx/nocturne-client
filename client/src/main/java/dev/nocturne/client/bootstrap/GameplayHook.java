package dev.nocturne.client.bootstrap;

import dev.nocturne.bootstrap.GameplaySink;

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

        default boolean entityRenderGate(Object entity) {
            return true;
        }

        /** {@code shouldRender(Entity,Frustum,DDD)Z} 的两参形态；第二个形参只用于消耗签名。 */
        default boolean entityRenderGate(Object entity, Object frustum) {
            return entityRenderGate(entity);
        }

        default void entityRender(Object entity, boolean post) {
        }

        /**
         * 世界空间 3D 绘制回调；默认空实现（没有后端/模块时不产生任何副作用）。
         *
         * @param partialTicks 当帧渲染插值量
         */
        default void worldRender3D(float partialTicks) {
        }

        /**
         * 世界渲染头部：捕获当帧相机矩阵（此时仍是世界相机，未被手部渲染改写）。
         *
         * @param partialTicks 当帧渲染插值量
         */
        default void worldRender3DCapture(float partialTicks) {
        }

        default boolean xrayBlock(Object blockState, Object blockPos) {
            return true;
        }

        default void handTransform(Object pose, Object arm, float inverseHeight) {
        }

        default float cameraZoom(float distance) {
            return distance;
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

    /**
     * 本类副本的直连汇：只有「安装者与本副本用同一套接口」时才非空。
     *
     * <p>真正的权威状态在 bootstrap 独有的 {@link GameplaySink}——不同加载器各有一份本类，
     * 静态字段会分裂（26.3 真机 {@code sink=false} 即此）。
     */
    private static volatile Sink localSink;
    /** 跨加载器适配出的代理（本副本接口）；目标变化时重建。 */
    private static volatile Sink adaptedSink;
    /** {@link #adaptedSink} 对应的共享目标对象。 */
    private static volatile Object adaptedTarget;
    private static final ThreadLocal<Object[]> ATTACK_CONTEXT = new ThreadLocal<Object[]>();
    /** KeyboardInput#tick 的头部捕获值；末尾无参消费，避开 RETURN 处被裁掉的 this 槽。 */
    private static final ThreadLocal<Object> INPUT_TICK = new ThreadLocal<Object>();
    /** 实体渲染嵌套捕获栈；每个 chamsPre 压一项，chamsPost 弹一项。 */
    private static final ThreadLocal<java.util.ArrayDeque<Object>> CHAMS_STACK =
            new ThreadLocal<java.util.ArrayDeque<Object>>();
    /** {@code chamsPre(null)} 的哨兵：ArrayDeque 不接受 null，且 null 也必须占一个配对位。 */
    private static final Object NO_CHAMS_ENTITY = new Object();

    /**
     * 取当前线程的捕获栈，首次使用时创建。
     *
     * <p>刻意不用匿名 {@code ThreadLocal} 子类：GameplayHook 被复制进 bootstrap 桥，
     * 匿名内部类 {@code GameplayHook$1} 不会随桥条目导出，{@code <clinit>} 直接
     * {@code NoClassDefFoundError}（1.8.9 真机实测）。普通 ThreadLocal + 显式判空可以避免
     * 任何额外 class 资源。
     */
    private static java.util.ArrayDeque<Object> chamsStack() {
        java.util.ArrayDeque<Object> stack = CHAMS_STACK.get();
        if (stack == null) {
            stack = new java.util.ArrayDeque<Object>();
            CHAMS_STACK.set(stack);
        }
        return stack;
    }
    /**
     * 捕获栈上限。渲染中途抛异常时注入的 RETURN 不会执行，那一项就永远配对不上；
     * 异常若持续发生，无上限的栈会每帧增长。正常嵌套深度远小于此值。
     */
    private static final int CHAMS_MAX_DEPTH = 64;
    /** sendPosition 是否已直接织入；状态在 bootstrap 的 {@link GameplaySink} 里跨加载器共享。 */
    /** 首个 Motion 事件是否已记录；用于区分"钩子未织入"与"事件未投递"。 */
    private static volatile boolean motionLogged;
    /** 首个 tick 事件是否已记录。 */
    private static volatile boolean tickLogged;

    private GameplayHook() {
    }

    public static void install(Sink replacement) {
        localSink = replacement;
        // 跨加载器共享：织入的字节码可能落在另一个副本上，必须让它们也能拿到汇。
        GameplaySink.install(replacement);
    }

    /**
     * 取本副本可用的汇。
     *
     * <p>优先直连（安装者与本副本同一接口，零开销）；否则从 bootstrap 的共享状态取目标，
     * 目标实现的是另一个副本的 Sink 时用本副本接口的代理转发。
     *
     * @return 汇实例；未安装时为 {@code null}（调用方按“放行”处理）
     */
    private static Sink sink() {
        // 自销毁后所有注入钩子入口统一退化为"放行"：闸门放在 bootstrap 唯一副本里，
        // 因此无论调用方是哪个加载器的 GameplayHook 副本都能读到。
        if (GameplaySink.isKilled()) {
            return null;
        }
        Sink direct = localSink;
        if (direct != null) {
            return direct;
        }
        Object shared = GameplaySink.target();
        if (shared == null) {
            return null;
        }
        if (shared instanceof Sink) {
            Sink typed = (Sink) shared;
            localSink = typed;
            return typed;
        }
        Sink cached = adaptedSink;
        if (cached != null && adaptedTarget == shared) {
            return cached;
        }
        Object made = GameplaySink.adapt(Sink.class);
        if (made instanceof Sink) {
            adaptedSink = (Sink) made;
            adaptedTarget = shared;
            return adaptedSink;
        }
        return null;
    }

    public static void markMotionDirect() {
        GameplaySink.markMotionDirect();
    }

    public static boolean isMotionDirect() {
        return GameplaySink.isMotionDirect();
    }

    /**
     * 触发自销毁闸门；幂等，可在任意线程调用。
     *
     * <p>只置 {@link GameplaySink#kill()} 的标志并清掉本副本的头部捕获缓存，不拆资源。
     */
    public static void kill() {
        GameplaySink.kill();
        ATTACK_CONTEXT.remove();
        INPUT_TICK.remove();
        CHAMS_STACK.remove();
    }

    /** @return 是否已进入自销毁状态 */
    public static boolean isKilled() {
        return GameplaySink.isKilled();
    }

    /** 登记一条"钩子已接线"。 */
    public static void markWired(String id) {
        GameplaySink.markWired(id);
    }

    /** 登记一条"钩子在当前版本不可用"。 */
    public static void markUnavailable(String id, String reason) {
        GameplaySink.markUnavailable(id, reason);
    }

    /** @return 该能力是否已真实接线 */
    public static boolean isWired(String id) {
        return GameplaySink.isWired(id);
    }

    /** @return 该能力是否已被明确标记为不可用 */
    public static boolean isUnavailable(String id) {
        return GameplaySink.isUnavailable(id);
    }

    /** @return 不可用原因；已接线或未登记时为 {@code null} */
    public static String unavailableReason(String id) {
        return GameplaySink.unavailableReason(id);
    }

    /** {@code shouldRender(Entity,Frustum,DDD)Z} 的两参织入点；Frustum 只占位。 */
    public static boolean entityRenderGate(Object entity, Object frustum) {
        if (GameplaySink.isKilled()) {
            return true;
        }
        try {
            Sink current = sink();
            return current == null || current.entityRenderGate(entity, frustum);
        } catch (Throwable t) {
            failure("entityRenderGate", t);
            return true;
        }
    }

    public static boolean playerTickPre(Object player) {
        try {
            if (!tickLogged) {
                tickLogged = true;
                System.out.println("[nocturne] gameplay hook first tick event; sink="
                        + (sink() != null));
            }
            Sink current = sink();
            return current == null || current.playerTickPre(player);
        } catch (Throwable t) {
            failure("playerTickPre", t);
            return true;
        }
    }

    public static void playerTickPost(Object player) {
        try {
            Sink current = sink();
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
                        + (sink() != null));
            }
            Sink current = sink();
            if (current != null) {
                current.motionPre(player);
            }
        } catch (Throwable t) {
            failure("motionPre", t);
        }
    }

    public static void motionPost(Object player) {
        try {
            Sink current = sink();
            if (current != null) {
                current.motionPost(player);
            }
        } catch (Throwable t) {
            failure("motionPost", t);
        }
    }

    /** 头部捕获 KeyboardInput 实例；末尾只调用无参 {@link #inputTick()}。 */
    public static void inputTickCapture(Object input) {
        if (input == null) {
            INPUT_TICK.remove();
        } else {
            INPUT_TICK.set(input);
        }
    }

    /** 末尾消费捕获值；多返回点时第二次调用为空操作。 */
    public static void inputTick() {
        Object input = INPUT_TICK.get();
        INPUT_TICK.remove();
        if (input != null) {
            inputTick(input);
        }
    }

    private static void inputTick(Object input) {
        try {
            Sink current = sink();
            if (current != null) {
                current.inputTick(input);
            }
        } catch (Throwable t) {
            failure("inputTick", t);
        }
    }

    public static boolean screenOpen(Object screen) {
        try {
            Sink current = sink();
            return current == null || current.screenOpen(screen);
        } catch (Throwable t) {
            failure("screenOpen", t);
            return true;
        }
    }

    public static void handTransform(Object pose, Object arm, float inverseHeight) {
        try {
            Sink current = sink();
            if (current != null) {
                current.handTransform(pose, arm, inverseHeight);
            }
        } catch (Throwable t) {
            failure("handTransform", t);
        }
    }

    public static float cameraZoom(float distance) {
        try {
            Sink current = sink();
            return current == null ? distance : current.cameraZoom(distance);
        } catch (Throwable t) {
            failure("cameraZoom", t);
            return distance;
        }
    }

    public static boolean noFog() {
        try {
            Sink current = sink();
            return current == null || current.noFog();
        } catch (Throwable t) {
            failure("noFog", t);
            return true;
        }
    }

    /** 1.8.9–1.12.2 的 {@code GameRenderer#setupFog(int,float)} 形参版本。 */
    public static boolean noFog(int fogType, float partialTicks) {
        return noFog();
    }

    public static boolean noHurtCamera() {
        try {
            Sink current = sink();
            return current == null || current.noHurtCamera();
        } catch (Throwable t) {
            failure("noHurtCamera", t);
            return true;
        }
    }

    /** 1.8.9–1.14.3 的 {@code GameRenderer#hurtCamera(float)} 形参版本。 */
    public static boolean noHurtCamera(float partialTicks) {
        return noHurtCamera();
    }

    public static float timerScale(float delta) {
        try {
            Sink current = sink();
            return current == null ? delta : current.timerScale(delta);
        } catch (Throwable t) {
            failure("timerScale", t);
            return delta;
        }
    }

    public static boolean xrayBlock(Object blockState, Object blockPos) {
        try {
            Sink current = sink();
            return current == null || current.xrayBlock(blockState, blockPos);
        } catch (Throwable t) {
            failure("xrayBlock", t);
            return true;
        }
    }

    public static boolean entityRenderGate(Object entity) {
        try {
            Sink current = sink();
            return current == null || current.entityRenderGate(entity);
        } catch (Throwable t) {
            failure("entityRenderGate", t);
            return true;
        }
    }

    /**
     * 世界空间 3D 绘制入口：织入点传入游戏自己的 {@code partialTicks}。
     *
     * <p>这里只做异常隔离与转发，GL 相关的一切都在 client/ui 侧；钩子内部绝不把异常抛回
     * 游戏的世界渲染方法。
     *
     * @param partialTicks 当帧渲染插值量
     */
    public static void worldRender3D(float partialTicks) {
        if (GameplaySink.isKilled()) {
            return;
        }
        try {
            Sink current = sink();
            if (current != null) {
                current.worldRender3D(partialTicks);
            }
        } catch (Throwable t) {
            failure("worldRender3D", t);
        }
    }

    /**
     * 世界渲染头部入口：转发给客户端捕获当帧相机矩阵。
     *
     * <p>与 {@link #worldRender3D(float)} 一样只做异常隔离与转发；GL 查询在后端完成。
     *
     * @param partialTicks 当帧渲染插值量（当前实现只做日志/对齐，捕获不依赖它）
     */
    public static void worldRender3DCapture(float partialTicks) {
        if (GameplaySink.isKilled()) {
            return;
        }
        try {
            Sink current = sink();
            if (current != null) {
                current.worldRender3DCapture(partialTicks);
            }
        } catch (Throwable t) {
            failure("worldRender3DCapture", t);
        }
    }

    public static void chamsPre(Object entity) {
        // 即使闸门已关也要压入哨兵：post 仍会在 RETURN 前被调用，少压一次就会弹掉外层实体。
        java.util.ArrayDeque<Object> stack = chamsStack();
        if (stack.size() >= CHAMS_MAX_DEPTH) {
            // 只可能是异常路径留下的未配对项：清掉，别让它无限增长。
            stack.clear();
        }
        stack.push(entity == null ? NO_CHAMS_ENTITY : entity);
        if (GameplaySink.isKilled()) {
            return;
        }
        entityRender(entity, false);
    }

    /** 末尾无参消费：弹出一项与本次 pre 配对；多个 RETURN 只有实际执行的那一个会调用。 */
    public static void chamsPost() {
        java.util.ArrayDeque<Object> stack = CHAMS_STACK.get();
        if (stack == null) {
            return;
        }
        Object entity = stack.poll();
        if (stack.isEmpty()) {
            CHAMS_STACK.remove();
        }
        if (entity != null && entity != NO_CHAMS_ENTITY) {
            entityRender(entity, true);
        }
    }

    private static void entityRender(Object entity, boolean post) {
        try {
            Sink current = sink();
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
            Sink current = sink();
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
            Sink current = sink();
            return current == null || (start ? current.destroyStart(blockPos, direction)
                    : current.destroyContinue(blockPos, direction));
        } catch (Throwable t) {
            failure(start ? "destroyStart" : "destroyContinue", t);
            return true;
        }
    }

    public static void destroyStop() {
        try {
            Sink current = sink();
            if (current != null) {
                current.destroyStop();
            }
        } catch (Throwable t) {
            failure("destroyStop", t);
        }
    }

    public static Object moveVec(Object entity, Object motion) {
        try {
            Sink current = sink();
            Object replacement = current == null ? motion : current.moveVec(entity, motion);
            return replacement == null ? motion : replacement;
        } catch (Throwable t) {
            failure("moveVec", t);
            return motion;
        }
    }

    public static void moveLegacyCapture(Object entity, double x, double y, double z) {
        try {
            Sink current = sink();
            if (current != null) {
                current.moveLegacyCapture(entity, x, y, z);
            }
        } catch (Throwable t) {
            failure("moveLegacyCapture", t);
        }
    }

    public static void moveLegacyDispatch(Object entity) {
        try {
            Sink current = sink();
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
            Sink current = sink();
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
            Sink current = sink();
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
            Sink current = sink();
            if (current != null) {
                current.attackPost(player, target);
            }
        } catch (Throwable t) {
            failure("attackPost", t);
        }
    }

    public static boolean leftClick() {
        try {
            Sink current = sink();
            return current == null || current.leftClick();
        } catch (Throwable t) {
            failure("leftClick", t);
            return true;
        }
    }

    public static boolean useItem() {
        try {
            Sink current = sink();
            return current == null || current.useItem();
        } catch (Throwable t) {
            failure("useItem", t);
            return true;
        }
    }

    public static boolean key(long window, int key, int scancode, int action, int modifiers) {
        try {
            Sink current = sink();
            return current == null || current.key(window, key, scancode, action, modifiers);
        } catch (Throwable t) {
            failure("key", t);
            return true;
        }
    }

    public static boolean key(long window, int action, Object event) {
        try {
            Sink current = sink();
            return current == null || current.key(window, action, event);
        } catch (Throwable t) {
            failure("key", t);
            return true;
        }
    }

    public static boolean mouse(long window, int button, int action, int modifiers) {
        try {
            Sink current = sink();
            return current == null || current.mouse(window, button, action, modifiers);
        } catch (Throwable t) {
            failure("mouse", t);
            return true;
        }
    }

    public static boolean mouse(long window, Object event, int action) {
        try {
            Sink current = sink();
            return current == null || current.mouse(window, event, action);
        } catch (Throwable t) {
            failure("mouse", t);
            return true;
        }
    }

    public static Object packetSend(Object packet) {
        try {
            Sink current = sink();
            return current == null ? packet : current.packetSend(packet);
        } catch (Throwable t) {
            failure("packetSend", t);
            return packet;
        }
    }

    public static Object packetReceive(Object packet) {
        try {
            Sink current = sink();
            return current == null ? packet : current.packetReceive(packet);
        } catch (Throwable t) {
            failure("packetReceive", t);
            return packet;
        }
    }

    /** 同一钩子两次失败日志之间的最小间隔（毫秒）。 */
    private static final long FAILURE_LOG_INTERVAL_MS = 5000L;
    /** 每个全局窗口最多打印的失败行数：按钩子名限流后仍需总量上限防刷屏。 */
    private static final int MAX_FAILURE_LINES_PER_WINDOW = 8;
    /** 钩子名 → 上次打印时间（单元素数组充当可变槽位）；钩子名是有限字面量，不会增长。 */
    private static final java.util.Map<String, long[]> failureLogs =
            new java.util.HashMap<String, long[]>();
    /** 全局窗口起点与窗口内已打印行数。 */
    private static long failureWindowStart;
    private static int failureWindowCount;

    /**
     * 钩子失败必须放行原游戏行为；日志**按钩子名**各限一个五秒窗口，另有每窗口总量上限，
     * 每个钩子首次失败补打一次栈。
     *
     * <p>为什么不是单个全局时间戳：高频钩子（{@code mouse} / {@code key}）先失败一次就会把窗口
     * 占满，其它钩子的首错被整段吞掉，排查时完全看不到真正坏掉的是哪一个。
     */
    static synchronized void failure(String hook, Throwable error) {
        Throwable cause = unwrap(error);
        long now = System.currentTimeMillis();
        long[] slot = failureLogs.get(hook);
        if (slot == null) {
            slot = new long[1];
            failureLogs.put(hook, slot);
        }
        if (now - slot[0] < FAILURE_LOG_INTERVAL_MS) {
            return;
        }
        if (now - failureWindowStart >= FAILURE_LOG_INTERVAL_MS) {
            failureWindowStart = now;
            failureWindowCount = 0;
        }
        if (failureWindowCount >= MAX_FAILURE_LINES_PER_WINDOW) {
            return;
        }
        slot[0] = now;
        failureWindowCount++;
        System.out.println("[nocturne] gameplay hook " + hook + " failed: " + cause);
        if (STACK_LOGGED.add(hook)) {
            // 只打一次栈：每五秒刷一次完整栈会把游戏日志淹掉，但完全没有栈又查不出真因
            // （例如 useItem 反复失败时只能看到 "UndeclaredThrowableException: null"）。
            cause.printStackTrace(System.out);
        }
    }

    /** 清空失败日志限流状态；仅供测试保证用例间互不影响。 */
    static synchronized void resetFailureLogsForTests() {
        failureLogs.clear();
        failureWindowStart = 0L;
        failureWindowCount = 0;
        STACK_LOGGED.clear();
    }

    /**
     * 剥掉反射包装：代理调用会把业务异常包成 {@link java.lang.reflect.InvocationTargetException}
     * 或 {@link java.lang.reflect.UndeclaredThrowableException}，只打外层等于没打。
     *
     * @param error 原始异常
     * @return 最内层原因（没有包装时就是它自己）
     */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.lang.reflect.InvocationTargetException
                    && ((java.lang.reflect.InvocationTargetException) current).getCause() != null) {
                current = ((java.lang.reflect.InvocationTargetException) current).getCause();
                continue;
            }
            if (current instanceof java.lang.reflect.UndeclaredThrowableException
                    && ((java.lang.reflect.UndeclaredThrowableException) current).getCause() != null) {
                current = ((java.lang.reflect.UndeclaredThrowableException) current).getCause();
                continue;
            }
            return current;
        }
        return error;
    }

    /** 已打印过栈的钩子名；每个钩子只补一次栈。 */
    private static final java.util.Set<String> STACK_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
}
