package dev.nocturne.client.event;

/** 实体渲染入口/返回点事件；post=false 为进入，true 为退出。 */
public final class EntityRenderEvent {
    public final Object entity;
    public final boolean post;

    public EntityRenderEvent(Object entity, boolean post) {
        this.entity = entity;
        this.post = post;
    }
}
