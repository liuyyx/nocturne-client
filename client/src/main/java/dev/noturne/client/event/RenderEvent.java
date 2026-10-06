package dev.noturne.client.event;

/**
 * 渲染帧事件：每 frame 投递一次（帧回调里），载荷为帧时间。
 *
 * <p>用途：ESP / Tracers / Chams 这类渲染显示模块的触发点——它们要在游戏画完世界、
 * HUD 开画之前拿到一次机会。当前仅立类型：投递点在叠加层绘制链路接上 P4-C 正式实现后
 * 再接入；此前的帧驱动仍走 {@code Module.onTick} 直调。
 */
public final class RenderEvent {

    /** 本帧时间戳（纳秒，{@code System.nanoTime} 基准）。 */
    public final long frameNanos;

    public RenderEvent(long frameNanos) {
        this.frameNanos = frameNanos;
    }
}
