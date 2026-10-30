package dev.nocturne.client.event;

/** 使用当前物品事件。 */
public final class UseItemEvent implements EventBus.Cancellable {

    /** true=使用调用完成后，false=使用调用开始前。 */
    public final boolean post;
    /** 为 true 时取消本次使用。 */
    public boolean cancelled;

    public UseItemEvent(boolean post) {
        this.post = post;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
