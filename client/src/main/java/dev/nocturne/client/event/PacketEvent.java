package dev.nocturne.client.event;

/**
 * 数据包事件：收发网络包时投递（方向见 {@link Direction}）。
 *
 * <p>当前仅立类型、暂无生产投递点：包钩子是 P6-2 战斗/走位模块的前置条件（KillAura 的目标选择、
 * Velocity 的包修改都挂在这里）。投递点接入前，订阅者收不到任何事件——这是预期的，
 * 不是静默失败。
 *
 * <p>{@link #cancelled} 由总线执行（实现 {@link EventBus.Cancellable}）：置位后默认订阅者不再接收，
 * 显式选择接收取消事件的订阅者除外。接入真正的包钩子之后，投递方据此决定是否放行这次收发。
 */
public final class PacketEvent implements EventBus.Cancellable {

    /** 包方向。 */
    public enum Direction {
        /** 客户端 → 服务端。 */
        SEND,
        /** 服务端 → 客户端。 */
        RECEIVE
    }

    /** 包方向。 */
    public final Direction direction;
    /** 包对象（游戏侧类型，不做解析，需要的模块自己按映射表读字段）。 */
    public final Object packet;
    /** 为 true 时取消本次收发（仅 SEND 生效，RECEIVE 取消只跳过后续订阅者）。 */
    public boolean cancelled;

    public PacketEvent(Direction direction, Object packet) {
        this.direction = direction;
        this.packet = packet;
    }

    /** @return 本次事件是否已被某个订阅者取消（总线据此停止后续投递） */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
