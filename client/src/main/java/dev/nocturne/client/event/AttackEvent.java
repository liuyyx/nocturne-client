package dev.nocturne.client.event;

/** 玩家攻击实体事件；目标保持游戏侧原对象，避免在事件层引入版本类型。 */
public final class AttackEvent implements EventBus.Cancellable {

    /** 被攻击的实体。 */
    public final Object target;
    /** true=攻击调用完成后，false=攻击调用开始前。 */
    public final boolean post;
    /** 为 true 时取消本次攻击。 */
    public boolean cancelled;

    public AttackEvent(Object target, boolean post) {
        this.target = target;
        this.post = post;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
