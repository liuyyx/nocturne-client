package dev.nocturne.client.event;

/** 当前客户端世界对象身份发生变化时投递。 */
public final class WorldChangeEvent {

    public static final WorldChangeEvent INSTANCE = new WorldChangeEvent();

    private WorldChangeEvent() {
    }
}
