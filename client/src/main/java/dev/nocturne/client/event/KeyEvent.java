package dev.nocturne.client.event;

/** 键盘按键事件；键码统一使用 AWT VK 空间。 */
public final class KeyEvent implements EventBus.Cancellable {

    public final int keyCode;
    public final boolean pressed;
    /** 为 true 时阻止本次输入继续进入游戏。 */
    public boolean cancelled;

    public KeyEvent(int keyCode, boolean pressed) {
        this.keyCode = keyCode;
        this.pressed = pressed;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
