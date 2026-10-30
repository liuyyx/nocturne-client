package dev.nocturne.client.event;

/** 本地玩家逻辑 tick 的前后沿；两个方向都是精确事件类型。 */
public final class PlayerTickEvent {

    private PlayerTickEvent() {
    }

    /** 玩家 tick 方法体执行前。 */
    public static final class Pre {
        public static final Pre INSTANCE = new Pre();

        private Pre() {
        }
    }

    /** 玩家 tick 方法体执行后。 */
    public static final class Post {
        public static final Post INSTANCE = new Post();

        private Post() {
        }
    }
}
