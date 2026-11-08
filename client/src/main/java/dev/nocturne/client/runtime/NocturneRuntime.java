package dev.nocturne.client.runtime;

import dev.nocturne.client.NocturneClient;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 帧回调的对外入口：**状态全部在 bootstrap 层的 {@link FrameDispatcher}**，本类只做转发与模块 tick。
 *
 * <p>为什么不把静态状态留在本类：注入到游戏方法里的那条调用由游戏的**隔离类加载器**解析
 * （Fabric 的 KnotClassLoader），而该加载器会把认得的所有 jar 各加载一遍——本类因此存在两份，
 * 静态状态各存一份。实测现象：帧回调连续触发，但注册进列表的动作在分发时读到的是另一份空列表，
 * 界面永远不出现。
 *
 * <p>因此这里**强制从 bootstrap 取分发器**：{@code Class.forName(name, true, null)} 的第三个参数
 * 是 null（bootstrap 加载器），绕过游戏加载器的拦截。对外只传 {@link Runnable}（JDK 类型），
 * 避免跨加载器传自定义接口时出现"接口类不同"的类型错误。
 *
 * <p>被注入字节码调用的是 {@link #onFrame()}：分发一帧并广播渲染事件；模块 tick 由
 * {@link ModuleTicker} 作为普通监听器挂在同一个分发器上，1.8.9 的帧钩子也能驱动。
 */
public final class NocturneRuntime {

    /** bootstrap 层分发器的全限定名。 */
    private static final String DISPATCHER_CLASS = "dev.nocturne.client.runtime.FrameDispatcher";

    /** 监听器 → 注册到 bootstrap 的包装动作：注销时需要同一个对象，故按身份缓存。 */
    private static final Map<FrameListener, Runnable> WRAPPERS = new HashMap<FrameListener, Runnable>();

    /** 已解析的 bootstrap 分发器方法；null 表示尚未解析或不可用。 */
    private static Method dispatchMethod;
    private static Method addMethod;
    private static Method removeMethod;
    private static Method countMethod;
    private static Method traceMethod;
    /** {@code FrameDispatcher.setDrawContextSink(Consumer)}；未解析时为 null。 */
    private static Method setDrawContextSinkMethod;
    /** {@code FrameDispatcher.setDrawContext(Object)}；未解析时为 null。 */
    private static Method setDrawContextMethod;
    /** {@code FrameDispatcher.clear()}；未解析时为 null。 */
    private static Method clearMethod;
    /** {@code FrameDispatcher.addShutdown(Runnable)}；未解析时为 null。 */
    private static Method addShutdownMethod;
    /** {@code FrameDispatcher.restoreShutdownActionsOnGameThread(long)}；未解析时为 null。 */
    private static Method restoreOnGameThreadMethod;
    /** {@code FrameDispatcher.runOnGameThread(Runnable,long)}；未解析时为 null。 */
    private static Method runOnGameThreadMethod;
    /** {@code FrameDispatcher.kill()}；未解析时为 null。 */
    private static Method killMethod;
    /** {@code FrameDispatcher.isKilled()}；未解析时为 null。 */
    private static Method killedStateMethod;

    /** 工具类，禁止实例化。 */
    private NocturneRuntime() {
    }

    /**
     * 解析 bootstrap 层的分发器方法（幂等）。
     *
     * @return 可用时返回 true；bootstrap 层没有该类（理论上不会）时返回 false
     */
    /**
     * 按「本类加载器 → bootstrap → system」的顺序加载分发器。
     *
     * @return 分发器类
     * @throws ClassNotFoundException 三种加载器都找不到
     */
    private static Class<?> loadDispatcher() throws ClassNotFoundException {
        // 先取 bootstrap：agent 形态下 agent jar 同时挂在 system 搜索路径上，若先看 own
        // 会加载出 system 私有一份 FrameDispatcher，监听器列表与注入钩子的那份分裂。
        // mod 形态没有 bootstrap bridge，这里必然 CNFE，再回落到本类加载器。
        try {
            return Class.forName(DISPATCHER_CLASS, true, null);
        } catch (ClassNotFoundException ignored) {
            // 落到本类加载器。
        }
        ClassLoader own = NocturneRuntime.class.getClassLoader();
        if (own != null) {
            try {
                return Class.forName(DISPATCHER_CLASS, true, own);
            } catch (ClassNotFoundException ignored) {
                // 落到 system。
            }
        }
        return Class.forName(DISPATCHER_CLASS, true, ClassLoader.getSystemClassLoader());
    }

    private static synchronized boolean bindDispatcher() {
        if (dispatchMethod != null) {
            return true;
        }
        try {
            // 解析顺序：
            // 1) 本类加载器 —— Fabric mod 形态下所有类都在 KnotClassLoader，这是唯一存在的一份；
            //    直接取 bootstrap 会 ClassNotFoundException（实测每帧刷屏）。
            // 2) bootstrap —— agent 形态下分发器由 bootstrap bridge 提供，游戏加载器会拦截同名类，
            //    必须绕开它。
            // 3) system —— 兜底。
            Class<?> dispatcher = loadDispatcher();
            dispatchMethod = dispatcher.getMethod("dispatch");
            addMethod = dispatcher.getMethod("add", Runnable.class);
            removeMethod = dispatcher.getMethod("remove", Runnable.class);
            countMethod = dispatcher.getMethod("count");
            traceMethod = dispatcher.getMethod("trace", boolean.class);
            setDrawContextSinkMethod = dispatcher.getMethod("setDrawContextSink",
                    java.util.function.Consumer.class);
            setDrawContextMethod = dispatcher.getMethod("setDrawContext", Object.class);
            clearMethod = dispatcher.getMethod("clear");
            addShutdownMethod = dispatcher.getMethod("addShutdown", Runnable.class);
            try {
                restoreOnGameThreadMethod = dispatcher.getMethod(
                        "restoreShutdownActionsOnGameThread", long.class);
                runOnGameThreadMethod = dispatcher.getMethod("runOnGameThread",
                        Runnable.class, long.class);
            } catch (NoSuchMethodException older) {
                // 旧分发器没有这两个入口：还原会退化为在调用线程执行（clear() 的旧行为）。
                restoreOnGameThreadMethod = null;
                runOnGameThreadMethod = null;
            }
            killMethod = dispatcher.getMethod("kill");
            killedStateMethod = dispatcher.getMethod("isKilled");
            return true;
        } catch (Throwable t) {
            System.out.println("[nocturne] bootstrap dispatcher unavailable: " + t);
            return false;
        }
    }

    /**
     * 注册一个每帧监听器。
     *
     * <p>监听器被包装成 {@link Runnable} 后注册到 bootstrap 分发器：接口用 JDK 类型，跨加载器不会
     * 出现"同名接口但不是同一个类型"的问题。
     *
     * @param listener 待注册的监听器；为 {@code null} 时忽略
     */
    public static void addListener(FrameListener listener) {
        if (listener == null || !bindDispatcher()) {
            return;
        }
        synchronized (WRAPPERS) {
            Runnable wrapper = WRAPPERS.get(listener);
            if (wrapper == null) {
                wrapper = new ListenerAction(listener);
                WRAPPERS.put(listener, wrapper);
            }
            try {
                addMethod.invoke(null, wrapper);
                if (System.getProperty("nocturne.ui.debug") != null && countMethod != null) {
                    // 计数是判断「监听器真的进了分发器」的唯一直接证据：界面打开却一帧都不画，
                    // 要么没注册上、要么分发时读的是另一份列表，这里一眼能分开。
                    System.out.println("[nocturne] listener added: "
                            + listener.getClass().getSimpleName()
                            + " count=" + countMethod.invoke(null));
                }
            } catch (Throwable t) {
                System.out.println("[nocturne] addListener failed: " + t);
            }
        }
    }

    /**
     * 注销一个每帧监听器。
     *
     * @param listener 待移除的监听器；不存在时无副作用
     */
    public static void removeListener(FrameListener listener) {
        if (listener == null || !bindDispatcher()) {
            return;
        }
        Runnable wrapper;
        synchronized (WRAPPERS) {
            wrapper = WRAPPERS.remove(listener);
        }
        if (wrapper == null) {
            return;
        }
        try {
            removeMethod.invoke(null, wrapper);
        } catch (Throwable t) {
            System.out.println("[nocturne] removeListener failed: " + t);
        }
    }

    /** @return 当前已注册的监听器数量（读自 bootstrap 分发器），主要用于诊断。 */
    public static int listenerCount() {
        if (!bindDispatcher()) {
            return 0;
        }
        try {
            Object count = countMethod.invoke(null);
            return count instanceof Number ? ((Number) count).intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 开启或关闭首帧存活日志。 */
    public static void trace(boolean enabled) {
        if (!bindDispatcher()) {
            return;
        }
        try {
            traceMethod.invoke(null, Boolean.valueOf(enabled));
        } catch (Throwable ignored) {
            // 诊断开关失败不影响帧回调
        }
    }

    /**
     * 注册绘制上下文汇；转发到 bootstrap 分发器。
     *
     * <p>必须走 bootstrap：26.x 的绘制钩子由游戏的隔离加载器解析，本类因此存在两份，
     * 汇若存在本类的静态字段里，钩子写进去的是空副本（实测：界面"已打开"却一个像素都不画）。
     *
     * @param sink 接收当帧绘制上下文的消费者；{@code null} 表示注销
     */
    public static void setDrawContextSink(java.util.function.Consumer<Object> sink) {
        if (!bindDispatcher()) {
            return;
        }
        try {
            setDrawContextSinkMethod.invoke(null, sink);
        } catch (Throwable t) {
            System.out.println("[nocturne] setDrawContextSink failed: " + t);
        }
    }

    /**
     * 把当帧绘制上下文交给叠加层后端；转发到 bootstrap 分发器。
     *
     * <p>契约：绝不抛出异常——调用方是游戏的绘制路径。
     *
     * @param graphics 当帧的绘制上下文；{@code null} 表示本帧结束、释放引用
     */
    public static void setDrawContext(Object graphics) {
        if (!bindDispatcher()) {
            return;
        }
        try {
            setDrawContextMethod.invoke(null, graphics);
        } catch (Throwable ignored) {
            // 绘制路径上不得抛出；失败只表现为本帧不画
        }
    }

    /**
     * 注册一个"自销毁前执行"的恢复动作；转发到 bootstrap 分发器。
     *
     * <p>必须走 bootstrap：注册方在 agent 侧（游戏的隔离加载器），执行发生在自销毁路径上——只写本类的
     * 静态字段会各存一份，注册进去的动作在自销毁时根本不会被跑。
     *
     * @param action 恢复动作；为 {@code null} 或分发器不可用时无副作用
     */
    public static void addShutdown(Runnable action) {
        if (action == null || !bindDispatcher()) {
            return;
        }
        try {
            addShutdownMethod.invoke(null, action);
        } catch (Throwable t) {
            System.out.println("[nocturne] addShutdown failed: " + t);
        }
    }

    /**
     * 触发自销毁闸门：让分发器 {@code dispatch()} 与全部注入钩子立即变成放行空操作。
     *
     * <p>只置一个 volatile 标志，**不**拆任何资源、不做 GL 调用，因此可以在渲染线程上安全调用，
     * 也可以与正在执行的帧回调并发。真正的拆卸见 {@link #shutdown()}，必须在非渲染线程上调用。
     */
    public static void kill() {
        if (!bindDispatcher()) {
            return;
        }
        try {
            killMethod.invoke(null);
        } catch (Throwable t) {
            System.out.println("[nocturne] runtime kill failed: " + t);
        }
    }

    /** @return 是否已进入自销毁状态；分发器不可用时按未触发处理 */
    public static boolean isKilled() {
        if (!bindDispatcher() || killedStateMethod == null) {
            return false;
        }
        try {
            Object state = killedStateMethod.invoke(null);
            return state instanceof Boolean && (Boolean) state;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 自销毁：清空 bootstrap 层分发器的全部每帧动作与绘制上下文汇。
     *
     * <p>清空之后注入的字节码仍会每帧调到这里，但分发器列表是空的——一次空遍历，不绘制、不轮询输入、
     * 不 tick 模块。这是"看起来没注入过"最接近的做法：agent 无法从目标 JVM 里卸载自己，
     * 但可以让所有可观察行为停下。
     *
     * <p><b>必须在非渲染线程上调用</b>：清空会执行 shutdown 动作（恢复鼠标抓取、解绑 transformer、
     * 释放 GL sink）。与 {@link #kill()} 配合的顺序是「先 kill 再 shutdown」。
     */
    /**
     * 在游戏主线程执行全部 shutdown 还原动作（panic 前置），限时等待。
     *
     * <p>分发器不可用时无副作用：随后的 {@link #shutdown()} 仍会走旧路径尽力还原。
     *
     * @param timeoutMs 等待游戏线程的上限（毫秒）
     */
    public static void restoreInputOnGameThread(long timeoutMs) {
        if (!bindDispatcher() || restoreOnGameThreadMethod == null) {
            return;
        }
        try {
            restoreOnGameThreadMethod.invoke(null, Long.valueOf(timeoutMs));
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null ? t.getCause() : t;
            System.out.println("[nocturne] restore on game thread failed: " + cause);
            cause.printStackTrace(System.out);
        }
    }

    /**
     * 把任务投递到游戏主线程并限时等待；分发器不支持时退化为直接执行。
     *
     * @param task      任务
     * @param timeoutMs 等待上限（毫秒）
     */
    public static void runOnGameThread(Runnable task, long timeoutMs) {
        if (task == null) {
            return;
        }
        if (!bindDispatcher() || runOnGameThreadMethod == null) {
            task.run();
            return;
        }
        try {
            runOnGameThreadMethod.invoke(null, task, Long.valueOf(timeoutMs));
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null ? t.getCause() : t;
            System.out.println("[nocturne] runOnGameThread failed: " + cause);
            cause.printStackTrace(System.out);
            task.run();
        }
    }

    public static void shutdown() {
        if (!bindDispatcher()) {
            return;
        }
        try {
            clearMethod.invoke(null);
        } catch (Throwable t) {
            System.out.println("[nocturne] runtime shutdown failed: " + t);
        }
    }

    /**
     * 帧交换点是否在工作。
     *
     * <p>一旦观察到帧钩子（{@code Display.update} / {@code glfwSwapBuffers} / {@code SDL_GL_SwapWindow}）
     * 驱动过一帧，绘制钩子就不再重复驱动——GLFW 栈上两者同时存在，不区分会让 RenderEvent 与叠加层
     * 绘制每帧各跑两遍（真机表现：「开了 ESP/NameTags 之后像是游戏加速了」）。
     */
    private static volatile boolean frameHookSeen;

    /** 当前后端是否必须用当帧绘制上下文绘制（游戏绘制 API 后端为 true）。 */
    private static volatile boolean contextDriven;

    /** 上一秒统计窗口起点（纳秒）；{@code 0} 表示尚未开始。 */
    private static volatile long lastStatNanos;
    /** 当前统计窗口内的驱动次数。 */
    private static volatile int driveCount;
    /** 其中来自帧交换点的次数（区分双驱动）。 */
    private static volatile int frameDrives;
    /** 其中来自绘制钩子的次数。 */
    private static volatile int drawDrives;
    /** 绘制钩子被跳过的次数。 */
    private static volatile int drawSkips;
    /** 是否打印每秒帧驱动统计（{@code -Dnocturne.debug.frames}）；默认关闭，生产不打日志。 */
    private static final boolean FRAME_STAT =
            System.getProperty("nocturne.debug.frames") != null;

    /**
     * 由注入的字节码每帧调用一次。
     *
     * <p>契约：无论发生什么都不得抛出异常，也不得阻塞。分发本身由 bootstrap 分发器保证异常隔离
     * （单个监听器抛出的 Throwable 不会传播回被补丁的游戏方法）；之后按 20Hz 折算驱动模块 tick。
     */
    public static void onFrame() {
        if (isKilled()) {
            // 自销毁后仍会每帧被注入点调进来：必须在这里就返回，不能进入 drive() 的 GL 工作。
            return;
        }
        frameHookSeen = true;
        frameDrives++;
        drive();
    }

    /**
     * 由 GUI 绘制钩子每帧调用（{@code GuiDrawHook}）。
     *
     * <p>只在帧交换点**不存在**时才真正驱动：SDL 栈（26.x）按设计不注册帧钩子，Fabric mod 形态也没有
     * {@code Instrumentation}，那时绘制入口是唯一的每帧时机；而 GLFW 栈上帧钩子已经驱动过，再驱动一次
     * 就是同帧两遍。
     */
    public static void onDrawFrame() {
        if (isKilled()) {
            return;
        }
        if (frameHookSeen && !contextDriven) {
            drawSkips++;
            return;
        }
        drawDrives++;
        drive();
    }

    /**
     * 标记当前后端是否**必须**用当帧绘制上下文绘制。
     *
     * <p>游戏绘制 API 后端（`gui-graphics` / `gui-stack` / `gui-extractor`）的上下文只在
     * 绘制钩子那一次调用内有效，错过了什么都画不出来；自绘 GL 的后端（`gl-core` / `gl-fixed`）
     * 不需要上下文，帧钩子已经画过，再画一遍就是同帧两遍。
     *
     * @param required {@code true} = 绘制钩子必须驱动
     */
    public static void setContextDriven(boolean required) {
        contextDriven = required;
    }

    /** 每秒打印一次帧驱动次数与来源；仅在 {@link #FRAME_STAT} 时工作。 */
    private static void statFrames() {
        if (!FRAME_STAT) {
            return;
        }
        driveCount++;
        long now = System.nanoTime();
        if (lastStatNanos == 0L) {
            lastStatNanos = now;
            return;
        }
        if (now - lastStatNanos < 1_000_000_000L) {
            return;
        }
        System.out.println("[nocturne] frame drive/s=" + driveCount
                + " (frameHook=" + frameDrives + " drawHook=" + drawDrives
                + " drawSkipped=" + drawSkips + ")");
        driveCount = 0;
        frameDrives = 0;
        drawDrives = 0;
        drawSkips = 0;
        lastStatNanos = now;
    }

    /** 每帧实际工作：分发监听器 + 广播渲染事件。 */
    private static void drive() {
        statFrames();
        // 自销毁后不再做任何每帧工作：分发器确实已被清空，但本方法后半段的 RenderEvent 广播与模块
        // tick 既不经过分发器、也不看驱动闸门——其中 driveModules 还会按"是否在世界里"把闸门重新
        // 置回 true，于是 panic 关掉的闸门会在 50ms 内自动翻回来并永久保持（模块照 tick）。
        NocturneClient client = NocturneClient.get();
        if (client != null && client.isPanicked()) {
            return;
        }
        if (bindDispatcher()) {
            try {
                dispatchMethod.invoke(null);
            } catch (Throwable t) {
                // 反射层失败也不能把异常带回游戏方法；分发器内部还会兜一层。
                System.out.println("[nocturne] dispatch failed: " + t);
            }
        }
        // P6-2：每帧广播一次渲染事件（ESP/Tracers 这类世界叠加绘制的触发点）。
        // 广播异常由 EventBus 限流隔离，不会打断 tick；客户端尚未 boot 时静默跳过。
        postRenderEvent();
    }

    /** 广播 {@link dev.nocturne.client.event.RenderEvent}；客户端未就绪时无副作用。 */
    private static void postRenderEvent() {
        try {
            NocturneClient client = NocturneClient.get();
            if (client == null) {
                return;
            }
            client.events().post(new dev.nocturne.client.event.RenderEvent(System.nanoTime()));
        } catch (Throwable ignored) {
            // 事件系统自身异常不能带回游戏帧；EventBus 内部已有隔离，这里是最后一道门。
        }
    }

    /** 把监听器适配成 {@link Runnable}：注册与注销必须用同一个对象，故由 {@link #WRAPPERS} 缓存。 */
    private static final class ListenerAction implements Runnable {

        private final FrameListener listener;

        private ListenerAction(FrameListener listener) {
            this.listener = listener;
        }

        @Override
        public void run() {
            listener.onFrame();
        }

        @Override
        public String toString() {
            return "listener:" + listener.getClass().getName();
        }
    }
}
