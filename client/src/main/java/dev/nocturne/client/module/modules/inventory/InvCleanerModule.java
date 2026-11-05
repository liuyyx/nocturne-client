package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.NumberBounds;
import dev.nocturne.client.value.TextValue;

import java.util.Random;

/** 按黑名单从玩家背包真实丢弃物品。 */
public final class InvCleanerModule extends GameModule {
    private final BoundsNumberValue delay = add(new BoundsNumberValue(
            "Delay", new NumberBounds(100, 120), 1, 200, 1));
    private final TextValue blacklisted = add(new TextValue("Blacklisted",
            "string,bone,compass,feather,glass_bottle,enchanting_table,chest,anvil", 512));
    private final Random random = new Random();
    private long nextClick;

    public InvCleanerModule() {
        super("InvCleaner", Category.INVENTORY);
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (bridge == null || actions == null || bridge.containerOpen()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextClick) {
            return;
        }
        for (int slot = 9; slot <= 44; slot++) {
            if (!bridge.itemMatches(bridge.containerStack(slot), blacklisted.get())) {
                continue;
            }
            actions.clickSlot(slot, 0, "pickup");
            actions.clickSlot(-999, 0, "pickup");
            NumberBounds bounds = delay.get();
            nextClick = now + (long) (bounds.min() + random.nextDouble() * (bounds.max() - bounds.min()));
            return;
        }
    }
}
