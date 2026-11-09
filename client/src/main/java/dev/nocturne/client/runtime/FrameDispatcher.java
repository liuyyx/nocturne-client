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
 * <p><b>只依赖 JDK</b>：它会被放进 bootstrap 层，看不到 ASM / gson，也看不到本项目其它类。
 * 对外只接受 {@link Runnable}——JDK 类型在两个加载器眼里天然是同一个，避免跨加载器传自定义接口。
 */
public final class FrameDispatcher {

    /** 是否已自销毁（{@link #clear()}）：置位后分发立刻停，哪怕还在遍历旧快照。 */
    private static volatile boolean cleared;

    /**
     * 一次性安装令牌：跨加载器副本共享「某个安装动作已经做过」。
     *
     * <p>同一次点击可能触发两次注入（先 CLI 再 GUI，或 Fabric 把 agent jar 再定义一份），
     * 两侧各注册一个 {@code OverlayBootstrap} 就会有两套 listener 抢着安装/绘制。令牌必须放在
     * 本类（bootstrap 层唯一副本）里，agent 侧的静态字段在 Fabric 下会被重新定义成另一份。
     */
    private static final java.util.Set<String> INSTALL_CLAIMS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 尝试占用一个安装令牌。
     *
     * @param key 令牌名；{@code null} 视为占用失败
     * @return 本次调用是第一个占用者时为 {@code true}
     */
    public static boolean claimInstall(String key) {
        return key != null && INSTALL_CLAIMS.add(key);
    }

    /**
     * 释放安装令牌（安装失败/确定放弃时使用，让下一次注入可以重试）。
     *
     * @param key 令牌名；不存在时无副作用
     */
    public static void releaseClaim(String key) {
        if (key != null) {
            INSTALL_CLAIMS.remove(key);
        }
    }

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

    /**
     * 自销毁闸门：一旦置位，{@link #dispatch()} 直接返回，任何注入钩子都不再进入游戏/GL 路径。
     *
     * <p>顺序契约：拆除方**先**置这个标志（volatile，任意线程安全），**再**在专用线程上做
     * 模块副作用与 GL 状态的清理。反过来的话，Render 线程会在"拆一半"的状态下继续进来做 GL
     * （1.16.5 WER dump：frame listener 被摘除的同时 Render 线程仍在做 GL，疑似 use-after-free）。
     */
    private static volatile boolean KILLED;

    /** 自销毁前要执行的恢复动作：修那些由监听器施加、但监听器列表清空后就没人能修的外部状态。 */
    private static final CopyOnWriteArrayList<Runnable> SHUTDOWN_ACTIONS =
            new CopyOnWriteArrayList<Runnable>();

    /** 第一次 {@link #dispatch()} 的线程＝游戏主线程；还原鼠标/输入必须回到这个线程。 */
    private static volatile Thread frameThread;
    /** 待游戏线程执行的一次性任务（panic 前置还原等）。 */
    private static final java.util.Queue<Runnable> FRAME_TASKS =
            new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
    /** shutdown 动作是否已在游戏线程执行过：{@link #clear()} 不再重复跑。 */
    private static volatile boolean shutdownActionsExecuted;

    /** 异常日志的限流窗口（毫秒）。 */
    private static final long ERROR_LOG_INTERVAL_MS = 5000L;
    /** 上次打印异常日志的时间戳。 */
    private static volatile long lastErrorLog;

    /** 已打印过链接错误的「动作 + 错误」指纹：链接错误不会自愈，同一个只报一次。 */
    private static final java.util.Set<String> LINKAGE_REPORTED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 被静默掉的重复链接错误次数，供诊断读取。 */
    private static final AtomicLong LINKAGE_SUPPRESSED = new AtomicLong();

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
            // 新会话（重新注入）注册监听器即认为分发重新可用；自销毁后的旧字节码调用仍被
            // dispatch() 里的 cleared 检查挡住。
            cleared = false;
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

    /**
     * 触发自销毁闸门；幂等。
     *
     * <p>只置标志并停发帧事件，**不**拆卸任何 GL 资源：拆卸必须由调用方在非渲染线程上按
     * 「先解绑 transformer、再清状态」的顺序完成。
     */
    public static void kill() {
        KILLED = true;
    }

    /** @return 是否已进入自销毁状态 */
    public static boolean isKilled() {
        return KILLED;
    }

    /**
     * 把任务投递到游戏主线程执行，并限时等待。
     *
     * <p>为什么必须回到游戏线程：{@code MouseHandler.grabMouse}/{@code releaseMouse}、
     * 1.8.9 的 {@code Mouse.setGrabbed} 都要求调用方持有游戏/渲染线程的上下文。panic 可能从
     * GUI 线程或模块线程触发，直接在调用线程上抓鼠标会让指针状态在错误的线程上改写，真机表现
     * 为“panic 后视角/光标永久失效”。
     *
     * <p>任务只会执行一次：超时后如果游戏线程仍没取走，调用线程会代为执行并打 WARN；
     * 与游戏线程并发时用 CAS 保证不重复执行。
     *
     * @param task      待执行任务；{@code null} 时忽略
     * @param timeoutMs 最长等待毫秒数；{@code 0} 表示不等待（但仍会投递）
     */
    public static void runOnGameThread(Runnable task, long timeoutMs) {
        if (task == null) {
            return;
        }
        if (isFrameThread()) {
            runSafely(task);
            return;
        }
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean once = new AtomicBoolean(false);
        // 用 lambda 而不是匿名内部类：bootstrap bridge 只复制 FrameDispatcher.class 本身，
        // 匿名类会生成 FrameDispatcher$1 并要求 bootstrap 加载器找得到它（真机
        // NoClassDefFoundError: FrameDispatcher$1）。lambda 的合成方法就在本类里。
        FRAME_TASKS.add(() -> {
            if (!once.compareAndSet(false, true)) {
                return;
            }
            try {
                runSafely(task);
            } finally {
                done.countDown();
            }
        });
        boolean finished = false;
        try {
            finished = done.await(Math.max(0L, timeoutMs),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!finished && once.compareAndSet(false, true)) {
            System.out.println("[nocturne] WARNING: game thread unreachable within " + timeoutMs
                    + "ms; running restore on caller thread " + Thread.currentThread().getName());
            try {
                runSafely(task);
            } finally {
                done.countDown();
            }
        } else if (!finished) {
            // 游戏线程已开始执行（CAS 失败）：再给它一点时间收尾，避免和它并发抓鼠标。
            try {
                done.await(200L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return 当前线程是否是登记的游戏主线程 */
    public static boolean isFrameThread() {
        Thread current = frameThread;
        return current != null && current == Thread.currentThread();
    }

    /**
     * 在游戏线程执行全部 shutdown 还原动作（幂等），限时等待。
     *
     * <p>顺序：**逆序**执行。后注册的是叠加层的 {@code restoreInputState}，它会先解除输入短路；
     * 早先注册的 {@code ForeignScreenGuard.restore()} 再抓回鼠标/恢复焦点——顺序反了的话
     * grabMouse 会被仍然生效的输入短路挡住，指针永远抓不回来。
     *
     * <p>执行完再留一个很短的窗口（≤250ms），让 GUI 关闭后登记的“抓取重试”能在帧回调里跑几次。
     *
     * @param timeoutMs 等待游戏线程的上限（毫秒）
     */
    public static void restoreShutdownActionsOnGameThread(long timeoutMs) {
        if (shutdownActionsExecuted) {
            return;
        }
        shutdownActionsExecuted = true;
        final java.util.List<Runnable> actions =
                new java.util.ArrayList<Runnable>(SHUTDOWN_ACTIONS);
        java.util.Collections.reverse(actions);
        if (actions.isEmpty()) {
            return;
        }
        boolean onGameThread = isFrameThread();
        final java.util.concurrent.CountDownLatch finished =
                new java.util.concurrent.CountDownLatch(1);
        runOnGameThread(() -> {
            try {
                for (Runnable action : actions) {
                    runSafely(action);
                }
            } finally {
                finished.countDown();
            }
        }, timeoutMs);
        if (onGameThread) {
            return;
        }
        try {
            finished.await(250L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 执行一个任务并吞掉异常：还原路径绝不能把异常抛给调用方。 */
    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            report("frame task failed: " + t);
        }
    }

    /** 在游戏线程上清空待执行任务。 */
    private static void drainFrameTasks() {
        Runnable task;
        while ((task = FRAME_TASKS.poll()) != null) {
            task.run();
        }
    }

    /** 仅供测试：恢复闸门与列表的初始状态（生产路径不调用）。 */
    static void resetForTests() {
        KILLED = false;
        cleared = false;
        firstFrameLogged = false;
        SHUTDOWN_ACTIONS.clear();
        LISTENERS.clear();
        DRAW_CONTEXT_SINK = null;
        shutdownActionsExecuted = false;
        frameThread = null;
        FRAME_TASKS.clear();
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
        // 先置位：clear() 可能发生在 dispatch() 遍历快照的途中（panic 从某个监听器里调用），
        // 之后同一快照里的监听器绝不能再跑——它们会把刚恢复的输入状态再写坏一次。
        cleared = true;
        // 先跑恢复动作、再清列表：这些动作修的是"我们改过、但不在监听器列表里"的外部状态
        // （放开游戏鼠标抓取、1.8.9 的 inGameHasFocus）。清完列表就再没有任何每帧回调，
        // 那时想恢复也没人能做——顺序反了，输入会被永久卡住。
        // 恢复动作必须在**游戏线程**执行：panic 已经把它投递并等待过（幂等），这里通常直接跳过；
        // 没经过 panic 的调用（测试/异常路径）仍按同样语义处理。
        restoreShutdownActionsOnGameThread(500L);
        SHUTDOWN_ACTIONS.clear();
        LISTENERS.clear();
        INSTALL_CLAIMS.clear();
        DRAW_CONTEXT_SINK = null;
        FRAME_TASKS.clear();
    }

    /**
     * 注册一个"自销毁前执行"的恢复动作。
     *
     * <p>用途：某些副作用由每帧监听器施加（放开游戏鼠标抓取、改写 {@code inGameHasFocus}），但监听器
     * 本身不在 {@link #clear()} 的可见范围内——自销毁后它们再也不会被驱动，副作用就永久留在那里。
     * 想被恢复的东西必须在这里登记。只接受 {@link Runnable}（JDK 类型），跨加载器传参安全。
     *
     * @param action 恢复动作；为 {@code null} 时忽略
     */
    public static void addShutdown(Runnable action) {
        if (action != null) {
            SHUTDOWN_ACTIONS.addIfAbsent(action);
        }
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
        frameThread = Thread.currentThread();
        // 先把投递到游戏线程的一次性任务跑掉（panic 还原）；即使已 KILLED 也要跑，
        // 否则超时兜底之外的还原任务会永远留在队列里。
        drainFrameTasks();
        if (KILLED) {
            // 自销毁后注入的加载点仍会每帧调到这里：一次 volatile 读就退出，不碰任何监听器。
            return;
        }
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
                if (KILLED || cleared) {
                    // panic 可能就从某个监听器内部调用 kill()/clear()：CopyOnWriteArrayList
                    // 的快照仍持有后续监听器，继续跑会把已经恢复的输入状态再次写坏。
                    break;
                }
                try {
                    action.run();
                } catch (LinkageError e) {
                    reportLinkage(action, e);
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

    /**
     * 链接错误（NoClassDefFoundError / ExceptionInInitializerError 派生）单独处理：
     * 这类错误不会自己恢复，每帧打印只会把日志刷成一片，真正的原因反而看不到。
     *
     * <p>每个「动作 + 错误类型 + 缺失类」只打印一次，并附带完整栈；后续同类错误静默计数
     * （{@link #LINKAGE_SUPPRESSED}）。刻意不摘掉监听器：界面仍是用户可见的功能，静默摘除
     * 等于让 ClickGUI 悄悄失效——这里只保证根因可见、日志不刷屏。
     *
     * @param action 抛错的动作
     * @param error  链接错误
     */
    private static void reportLinkage(Runnable action, LinkageError error) {
        String key = action.getClass().getName() + "|" + error.getClass().getName() + "|"
                + (error.getMessage() == null ? "" : error.getMessage());
        if (!LINKAGE_REPORTED.add(key)) {
            LINKAGE_SUPPRESSED.incrementAndGet();
            return;
        }
        System.out.println("[nocturne] frame action linkage failure: " + action + ": " + error
                + " (missing class or class-loader boundary; this action keeps failing,"
                + " further identical reports are counted silently)");
        error.printStackTrace(System.out);
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
