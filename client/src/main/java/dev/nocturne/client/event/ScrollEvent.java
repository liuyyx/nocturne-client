package dev.nocturne.client.event;

/** 游戏原生滚轮入口事件；在 GUI 累积之前同步投递。 */
public final class ScrollEvent {
    public final double delta;

    public ScrollEvent(double delta) {
        this.delta = delta;
    }
}
