package dev.noturne.client.event;

/**
 * 渲染帧事件：每 frame 投递一次（帧回调里），载荷为帧时间。
 *
 * <p>用途：ESP / Tracers / Chams 这类渲染显示模块的触发点——它们要在游戏画完世界、
 * HUD 开画之前拿到一次机会。投递点在 {@code NoturneRuntime.onFrame}（P6-2 已接入）。
 */
public final class RenderEvent {

    /** 本帧时间戳（纳秒，{@code System.nanoTime} 基准）。 */
    public final long frameNanos;

    public RenderEvent(long frameNanos) {
        this.frameNanos = frameNanos;
    }
}
