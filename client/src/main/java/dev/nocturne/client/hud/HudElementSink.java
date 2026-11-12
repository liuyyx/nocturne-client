package dev.nocturne.client.hud;

/** client 与 ui 之间的 HUD 元素汇；同 id 重复添加表示替换。 */
public interface HudElementSink {
    void addElement(HudElement element);

    void removeElement(String id);

    boolean hasElement(String id);
}
