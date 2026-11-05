package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.NumberBounds;

import java.util.Random;

/** 在副手没有图腾时，从背包选取图腾并执行真实 offhand swap。 */
public final class AutoTotemModule extends GameModule {
    private final BoundsNumberValue delay = add(new BoundsNumberValue(
            "Delay", new NumberBounds(100, 120), 50, 200, 1));
    private final Random random = new Random();
    private long nextClick;

    public AutoTotemModule() {
        super("AutoTotem", Category.INVENTORY);
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (bridge == null || actions == null || bridge.containerOpen()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextClick || bridge.itemDescription(bridge.inventoryStack(40)).contains("totem_of_undying")) {
            return;
        }
        for (int slot = 9; slot <= 35; slot++) {
            if (!bridge.itemDescription(bridge.inventoryStack(slot)).contains("totem_of_undying")) {
                continue;
            }
            actions.clickSlot(slot, 40, "swap");
            NumberBounds bounds = delay.get();
            nextClick = now + (long) (bounds.min() + random.nextDouble() * (bounds.max() - bounds.min()));
            return;
        }
    }
}
