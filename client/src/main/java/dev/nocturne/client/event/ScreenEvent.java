package dev.nocturne.client.event;

/** 游戏打开/关闭原生界面前沿；取消会阻止本次 setScreen/displayGuiScreen。 */
public final class ScreenEvent implements EventBus.Cancellable {

    /** 新界面；null 表示关闭当前界面。 */
    public final Object screen;
    public boolean cancelled;

    public ScreenEvent(Object screen) {
        this.screen = screen;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
