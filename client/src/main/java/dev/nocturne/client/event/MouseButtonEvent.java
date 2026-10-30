package dev.nocturne.client.event;

/** 鼠标按钮事件；按钮编号采用 0=左键、1=右键、2=中键。 */
public final class MouseButtonEvent implements EventBus.Cancellable {

    public final int button;
    public final boolean pressed;
    /** 为 true 时阻止本次输入继续进入游戏。 */
    public boolean cancelled;

    public MouseButtonEvent(int button, boolean pressed) {
        this.button = button;
        this.pressed = pressed;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
