package dev.nocturne.client.runtime;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.ModuleRegistry;

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
 * <p>被注入字节码调用的是 {@link #onFrame()}：先分发一帧，再按 20Hz 折算驱动模块 tick。
 */
public final class NocturneRuntime {

    /** bootstrap 层分发器的全限定名。 */
    private static final String DISPATCHER_CLASS = "dev.nocturne.client.runtime.FrameDispatcher";

    /** 模块 tick 的目标周期（纳秒）：游戏逻辑 tick 约 20Hz，帧率远高于此，故按时间累加折算。 */
    private static final long TICK_INTERVAL_NANOS = 50_000_000L;
    /** 上次驱动模块 tick 的时间戳（纳秒）；0 表示尚未驱动过。 */
    private static volatile long lastTickNanos;

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

    /** 工具类，禁止实例化。 */
    private NocturneRuntime() {
    }

    /**
     * 解析 bootstrap 层的分发器方法（幂等）。
     *
     * @return 可用时返回 true；bootstrap 层没有该类（理论上不会）时返回 false
     */
    private static synchronized boolean bindDispatcher() {
        if (dispatchMethod != null) {
            return true;
        }
        try {
            // 第三个参数为 null = 用 bootstrap 加载器：游戏加载器会拦截同名类，必须绕开它。
            Class<?> dispatcher = Class.forName(DISPATCHER_CLASS, true, null);
            dispatchMethod = dispatcher.getMethod("dispatch");
            addMethod = dispatcher.getMethod("add", Runnable.class);
            removeMethod = dispatcher.getMethod("remove", Runnable.class);
            countMethod = dispatcher.getMethod("count");
            traceMethod = dispatcher.getMethod("trace", boolean.class);
            setDrawContextSinkMethod = dispatcher.getMethod("setDrawContextSink",
                    java.util.function.Consumer.class);
            setDrawContextMethod = dispatcher.getMethod("setDrawContext", Object.class);
            clearMethod = dispatcher.getMethod("clear");
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
     * 自销毁：清空 bootstrap 层分发器的全部每帧动作与绘制上下文汇。
     *
     * <p>清空之后注入的字节码仍会每帧调到这里，但分发器列表是空的——一次空遍历，不绘制、不轮询输入、
     * 不 tick 模块。这是"看起来没注入过"最接近的做法：agent 无法从目标 JVM 里卸载自己，
     * 但可以让所有可观察行为停下。
     */
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
     * 由注入的字节码每帧调用一次。
     *
     * <p>契约：无论发生什么都不得抛出异常，也不得阻塞。分发本身由 bootstrap 分发器保证异常隔离
     * （单个监听器抛出的 Throwable 不会传播回被补丁的游戏方法）；之后按 20Hz 折算驱动模块 tick。
     */
    public static void onFrame() {
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
        driveModules();
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

    /**
     * 按游戏逻辑频率（约 20Hz）驱动模块 tick。
     *
     * <p>接口契约：模块注册表暴露无参的 {@code tick()}，自身负责激活闸门与逐模块异常隔离
     * （AutoRespawn 之类需要在死亡界面继续运行的模块由注册表豁免）。客户端尚未 boot 时静默跳过。
     */
    private static void driveModules() {
        long now = System.nanoTime();
        if (lastTickNanos != 0L && now - lastTickNanos < TICK_INTERVAL_NANOS) {
            return;
        }
        lastTickNanos = now;
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        GameBridge bridge = client.gameBridge();
        boolean inWorld = bridge != null && bridge.isResolved();
        boolean wasInWorld = false;
        ModuleRegistry registry = client.modules();
        try {
            wasInWorld = registry.isActive();
        } catch (Throwable ignored) {
            // 闸门查询失败不改变行为，维持上一次判断
        }
        if (inWorld != wasInWorld) {
            registry.setActive(inWorld);
        }
        // P6-1：tick 先走总线广播（跨模块协作挂这里），再走直调（存量模块的 onTick）。
        // 广播异常由 EventBus 限流隔离，不会打断直调。
        client.events().post(dev.nocturne.client.event.TickEvent.INSTANCE);
        client.modules().tick();
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
