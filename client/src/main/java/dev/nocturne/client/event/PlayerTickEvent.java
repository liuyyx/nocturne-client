package dev.nocturne.client.event;

/** 本地玩家逻辑 tick 的前后沿；两个方向都是精确事件类型。 */
public final class PlayerTickEvent {

    private PlayerTickEvent() {
    }

    /** 玩家 tick 方法体执行前；取消会跳过整个 LocalPlayer.tick，只供跳刻类功能使用。 */
    public static final class Pre implements EventBus.Cancellable {
        /** 为 true 时取消整个本地玩家 tick。 */
        public boolean cancelled;

        public Pre() {
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** 玩家 tick 方法体执行后。 */
    public static final class Post {
        public static final Post INSTANCE = new Post();

        private Post() {
        }
    }
}
