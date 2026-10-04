package dev.noturne.client.runtime;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行时入口，由注入的字节码调用。
 *
 * <p>agent 会修改游戏主循环，使其每帧调用一次 {@link #onFrame()}。该方法位于热路径上，且运行在我们
 * 无法控制的 JVM 方法内部，因此刻意设计为防御式：绝不抛异常、绝不阻塞，且在注册监听器之前不做任何事。
 * 该类是各客户端模块接入游戏帧循环的统一挂载点。
 */
public final class NoturneRuntime {

    /** 每帧回调的监听器集合。采用写时复制列表，使帧线程遍历期间可安全增删。 */
    private static final List<FrameListener> LISTENERS = new CopyOnWriteArrayList<FrameListener>();
    /** 是否打印帧钩子存活日志的开关，见 {@link #trace(boolean)}。 */
    private static final AtomicBoolean TRACE = new AtomicBoolean(false);
    /** 是否已记录过“首帧”日志，保证只打印一次（volatile 确保帧线程可见）。 */
    private static volatile boolean firstFrameLogged;

    /** 工具类，禁止实例化。 */
    private NoturneRuntime() {
    }

    /**
     * 注册一个每帧监听器。
     *
     * @param listener 待注册的监听器；为 {@code null} 时忽略
     */
    public static void addListener(FrameListener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    /**
     * 注销一个每帧监听器。
     *
     * @param listener 待移除的监听器；不存在时无副作用
     */
    public static void removeListener(FrameListener listener) {
        LISTENERS.remove(listener);
    }

    /** @return 当前已注册的监听器数量，主要用于诊断。 */
    public static int listenerCount() {
        return LISTENERS.size();
    }

    /**
     * 开启或关闭首帧存活日志。
     *
     * <p>钩子首次真正触发时打印一行日志，用于确认字节码补丁是否生效。
     *
     * @param enabled 是否启用
     */
    public static void trace(boolean enabled) {
        TRACE.set(enabled);
    }

    /**
     * 由注入的字节码每帧调用一次。
     *
     * <p>契约：无论发生什么都不得抛出异常，也不得阻塞。单个监听器抛出的任何 {@link Throwable} 都会被
     * 吞掉，以免一个损坏的覆盖层拖垮游戏或影响其他监听器。未注册任何监听器时为空操作。
     */
    public static void onFrame() {
        try {
            if (!firstFrameLogged) {
                firstFrameLogged = true;
                if (TRACE.get()) {
                    System.out.println("[noturne] frame hook is live");
                }
            }
            if (LISTENERS.isEmpty()) {
                return;
            }
            for (int i = 0; i < LISTENERS.size(); i++) {
                try {
                    LISTENERS.get(i).onFrame();
                } catch (Throwable ignored) {
                    // 单个监听器出错不得中断其余监听器，也不能影响游戏
                }
            }
        } catch (Throwable ignored) {
            // 绝不把异常传播回被补丁的游戏方法
        }
    }
}
