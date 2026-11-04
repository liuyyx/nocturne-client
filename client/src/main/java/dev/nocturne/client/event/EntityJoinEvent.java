package dev.nocturne.client.event;

/** 实体加入客户端世界前沿；id 在省略实体 id 的新版本为 -1。 */
public final class EntityJoinEvent {

    public final int id;
    public final Object entity;

    public EntityJoinEvent(int id, Object entity) {
        this.id = id;
        this.entity = entity;
    }
}
