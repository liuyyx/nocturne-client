package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.TextValue;

/** 取消允许物品的 RELEASE_USE_ITEM，避免使用状态被意外释放。 */
public final class NoItemReleaseModule extends GameModule {
    private final TextValue allowedItems = add(new TextValue("Allowed items", "swords,food,potions", 512));

    public NoItemReleaseModule() {
        super("NoItemRelease", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, this::onPacket);
    }

    private void onPacket(PacketEvent event) {
        GameBridge bridge = bridge();
        if (event.direction != PacketEvent.Direction.SEND || bridge == null
                || !bridge.isReleaseUseItem(event.packet)) {
            return;
        }
        Object held = bridge.heldItem(bridge.player(), false);
        if (bridge.containerOpen() || bridge.itemMatches(held, allowedItems.get())) {
            event.cancelled = true;
        }
    }
}
