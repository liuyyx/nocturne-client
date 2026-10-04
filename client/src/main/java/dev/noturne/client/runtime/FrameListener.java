package dev.noturne.client.runtime;

/**
 * 每帧回调接口。
 *
 * <p>由 UI 层实现，调用方是 agent 注入到游戏主循环中的字节码，因此实现必须足够轻量，且不得在热路径上
 * 产生分配。任何异常都会被 {@link NoturneRuntime} 吞掉——一个损坏的覆盖层绝不能让游戏崩溃。
 */
public interface FrameListener {

    void onFrame();

    /**
     * 每帧执行一次的回调。
     *
     * <p>运行在游戏主线程上，实现不应阻塞、不应做 I/O；若耗时过长会直接拖慢游戏帧率。
     * 抛出的任何异常都已被调用方隔离，此处无需自行 try/catch 处理用户输入错误。
     */
}
