package dev.nocturne.client.runtime;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 帧分发的**中立实现**：静态状态必须全局只有一份，否则"注册"与"分发"看到的是两个列表。
 *
 * <p>为什么必须单独一个类、且只能被 bootstrap 加载：agent 的类由系统类加载器加载，而**注入到游戏
 * 方法里的那条调用**由游戏的隔离类加载器（Fabric 的 {@code KnotClassLoader}）解析。Fabric 会把它
 * 认得的所有 jar 都自己加载一遍（即便该 jar 是 agent jar），因此任何放在 jar 里的类都会出现两份。
 * 实测现象：帧回调连续触发，但 {@code listeners} 恒为 0，界面永远不出现。
 *
 * <p>本类被追加到 **bootstrap** 搜索路径（见 {@code NocturneAgent.installBootstrapBridge}），并由
 * {@link NocturneRuntime} 通过 {@code Class.forName(..., true, null)} **强制从 bootstrap** 取用——
 * 这样无论调用方来自哪个加载器，拿到的都是同一个类对象。
 *
 * <p><b>只依赖 JDK</b>：它会被放进 bootstrap 层，看不到 Skija / ASM / gson，也看不到本项目其它类。
 * 对外只接受 {@link Runnable}——JDK 类型在两个加载器眼里天然是同一个，避免跨加载器传自定义接口。
 */
public final class FrameDispatcher {

    /** 每帧回调的动作集合。写时复制列表，使帧线程遍历期间可安全增删。 */
    private static final CopyOnWriteArrayList<Runnable> LISTENERS =
            new CopyOnWriteArrayList<Runnable>();

    /** 同一线程重入保护：回调里又触发分发时直接跳过，避免无限递归。 */
    private static final ThreadLocal<Boolean> IN_FRAME = new ThreadLocal<Boolean>();

    /** 跨来源驱动保护：同帧的并发驱动只允许一个进行；用 tryLock 保证帧线程绝不阻塞。 */
    private static final ReentrantLock FRAME_LOCK = new ReentrantLock();

    /** 异常日志限流计数，避免故障时每帧刷屏。 */
    private static final AtomicLong ERROR_COUNT = new AtomicLong();

    /** 是否打印"帧钩子存活"日志（只打一次）。 */
    private static final AtomicBoolean TRACE = new AtomicBoolean(false);

    /** 是否已打印过首帧存活日志。 */
    private static volatile boolean firstFrameLogged;

    /** 异常日志的限流窗口（毫秒）。 */
    private static final long ERROR_LOG_INTERVAL_MS = 5000L;
    /** 上次打印异常日志的时间戳。 */
    private static volatile long lastErrorLog;

    /** 诊断用：已记录的分发次数（只记前几帧，避免每帧写盘）。 */
    private static final java.util.concurrent.atomic.AtomicInteger DIAG_FRAMES =
            new java.util.concurrent.atomic.AtomicInteger();

    private FrameDispatcher() {
    }

    /**
     * 注册一个每帧动作；同一对象重复注册会被忽略。
     *
     * @param action 每帧执行的动作；为 {@code null} 时忽略
     */
    public static void add(Runnable action) {
        if (action != null) {
            LISTENERS.addIfAbsent(action);
        }
    }

    /** 注销一个每帧动作；不存在时无副作用。 */
    public static void remove(Runnable action) {
        LISTENERS.remove(action);
    }

    /** @return 当前已注册的动作数量，主要用于诊断。 */
    public static int count() {
        return LISTENERS.size();
    }

    /** 开启或关闭首帧存活日志。 */
    public static void trace(boolean enabled) {
        TRACE.set(enabled);
    }

    /**
     * 绘制上下文汇：叠加层后端在这里收当帧的绘制上下文。
     *
     * <p>和监听器列表同理，**必须放在本类**：26.x 的绘制钩子由游戏的隔离加载器解析，
     * 而叠加层后端由 agent 的加载器持有——各自存一份静态字段的话，钩子写进去的是空副本，
     * 后端永远收不到上下文（实测现象：界面"已打开"但一个像素都不画，日志里只有
     * "overlay opened but renderer not ready"）。
     *
     * <p>只收 {@link java.util.function.Consumer}（JDK 类型）：两个加载器眼里它就是同一个类型，
     * 不会出现"自定义接口类不同"的类型错误。
     */
    private static volatile java.util.function.Consumer<Object> DRAW_CONTEXT_SINK;

    /**
     * 注册绘制上下文汇；后注册的覆盖先前的。
     *
     * @param sink 接收当帧绘制上下文的消费者；{@code null} 表示注销
     */
    public static void setDrawContextSink(java.util.function.Consumer<Object> sink) {
        DRAW_CONTEXT_SINK = sink;
    }

    /**
     * 把当帧绘制上下文交给叠加层后端；未注册汇时无副作用。
     *
     * @param graphics 当帧的绘制上下文；{@code null} 表示本帧结束、释放引用
     */
    public static void setDrawContext(Object graphics) {
        java.util.function.Consumer<Object> sink = DRAW_CONTEXT_SINK;
        if (sink != null) {
            sink.accept(graphics);
        }
    }

    /**
     * 清空全部每帧动作与绘制上下文汇。
     *
     * <p>给"自销毁"用：注入的字节码仍然每帧调用 {@code onFrame()}，但列表空了之后就是一次空遍历
     * ——GUI 不再绘制、开关键不再响应、模块不再 tick，行为上与"没注入过"一致。
     */
    public static void clear() {
        LISTENERS.clear();
        DRAW_CONTEXT_SINK = null;
    }

    /**
     * 分发一帧给全部动作。
     *
     * <p>契约：无论发生什么都不得抛出异常，也不得阻塞——本方法由注入的字节码在游戏的缓冲交换点调用，
     * 一个逃逸的异常会把游戏崩在任意位置。
     */
    public static void dispatch() {
        // 文件诊断（diag3）已删除：生产环境每会话前 3 帧写 nocturne-diag.txt 是残留 IO；
        // 首帧状态由 TRACE 日志与叠加层的 overlay first frame 行覆盖。
        if (Boolean.TRUE.equals(IN_FRAME.get())) {
            report("re-entrant dispatch ignored");
            return;
        }
        if (!FRAME_LOCK.tryLock()) {
            return;
        }
        IN_FRAME.set(Boolean.TRUE);
        try {
            if (!firstFrameLogged && TRACE.get()) {
                firstFrameLogged = true;
                System.out.println("[nocturne] frame hook is live");
            }
            for (Runnable action : LISTENERS) {
                try {
                    action.run();
                } catch (Throwable t) {
                    report("frame action failed: " + action.getClass().getName() + ": " + t);
                }
            }
        } catch (Throwable t) {
            report("frame dispatch failed: " + t);
        } finally {
            IN_FRAME.remove();
            FRAME_LOCK.unlock();
        }
    }

    /** 限流记录一条异常；同一窗口内只打印一次，避免故障时每帧刷屏。 */
    private static void report(String message) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLog < ERROR_LOG_INTERVAL_MS && ERROR_COUNT.get() > 0) {
            ERROR_COUNT.incrementAndGet();
            return;
        }
        lastErrorLog = now;
        ERROR_COUNT.incrementAndGet();
        System.out.println("[nocturne] " + message);
    }
}
