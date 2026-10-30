package dev.nocturne.client.event;

/**
 * 数据包事件：收发网络包时投递（方向见 {@link Direction}）。
 *
 * <p>包对象可由订阅者替换；发送与接收方向的取消都会阻止原调用继续执行。
 *
 * <p>{@link #cancelled} 由总线执行（实现 {@link EventBus.Cancellable}）：置位后默认订阅者不再接收，
 * 显式选择接收取消事件的订阅者除外。投递方据此决定是否放行这次收发。
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
    public Object packet;
    /** 为 true 时取消本次收发。 */
    public boolean cancelled;

    public PacketEvent(Direction direction, Object packet) {
        this.direction = direction;
        this.packet = packet;
    }

    /** 替换本次继续发送或接收的包对象。 */
    public void setPacket(Object packet) {
        this.packet = packet;
    }

    /** @return 本次事件是否已被某个订阅者取消 */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
