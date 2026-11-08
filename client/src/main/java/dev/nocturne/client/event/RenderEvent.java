package dev.nocturne.client.event;

/**
 * 渲染帧事件：每 frame 投递一次（帧回调里），载荷为帧时间与序号。
 *
 * <p>用途：ESP / Tracers / Chams 这类渲染显示模块的触发点——它们要在游戏画完世界、
 * HUD 开画之前拿到一次机会。投递点在 {@code NocturneRuntime} 的帧驱动里。
 *
 * <p>载荷**每帧复用同一个实例**（热路径不分配）：订阅者只能在回调内读取字段，不得跨帧保存事件
 * 引用。字段可写正是为此——每次投递前由发布方改写时间戳与序号。
 */
public final class RenderEvent {

    /** 本帧时间戳（纳秒，{@code System.nanoTime} 基准）。 */
    public long frameNanos;

    /** 帧序号（从 1 起单调递增）。 */
    public long sequence;

    /** 供复用实例改写载荷。 */
    public void update(long frameNanos, long sequence) {
        this.frameNanos = frameNanos;
        this.sequence = sequence;
    }
}
