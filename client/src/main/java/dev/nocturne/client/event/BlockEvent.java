package dev.nocturne.client.event;

/** 方块破坏阶段事件；位置与方向保持游戏原对象，由映射层保证跨版本。 */
public final class BlockEvent implements EventBus.Cancellable {

    public enum Phase { START, CONTINUE, STOP }

    public final Phase phase;
    public final Object blockPos;
    public final Object direction;
    /** STOP 阶段忽略该标志；START/CONTINUE 可取消本次游戏调用。 */
    public boolean cancelled;

    public BlockEvent(Phase phase, Object blockPos, Object direction) {
        this.phase = phase;
        this.blockPos = blockPos;
        this.direction = direction;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
