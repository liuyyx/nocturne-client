package dev.nocturne.client.event;

/**
 * 游戏逻辑节拍事件：约 20Hz，由 {@code NocturneRuntime.driveModules} 在帧回调里按时间折算投递。
 *
 * <p>与 {@code Module.onTick} 直调的区别：直调只覆盖模块，本事件是总线广播——跨模块协作
 * （例如连击模块读走位模块的状态）应订阅本事件，而不是互相持有引用。
 */
public final class TickEvent {

    /** 单例：事件无载荷，每 tick 复用同一实例，避免每 50ms 一次分配。 */
    public static final TickEvent INSTANCE = new TickEvent();

    private TickEvent() {
    }
}
