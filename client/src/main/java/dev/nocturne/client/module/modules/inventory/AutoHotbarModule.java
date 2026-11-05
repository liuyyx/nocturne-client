package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/** 按固定用途把背包物品交换到九个热栏槽。 */
public final class AutoHotbarModule extends GameModule {
    private static final String[] RULES = {
            "sword", "pickaxe", "axe", "shovel", "bow", "food", "potion", "blocks", "water_bucket"
    };
    private final NumberValue delay = add(new NumberValue("Delay", 110, 0, 300, 10));
    private long nextClick;

    public AutoHotbarModule() {
        super("AutoHotbar", Category.INVENTORY);
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
        for (int hotbar = 0; hotbar < RULES.length; hotbar++) {
            if (bridge.itemMatches(bridge.inventoryStack(hotbar), RULES[hotbar])) {
                continue;
            }
            for (int source = 9; source <= 35; source++) {
                if (!bridge.itemMatches(bridge.inventoryStack(source), RULES[hotbar])) {
                    continue;
                }
                actions.clickSlot(source, hotbar, "swap");
                nextClick = now + delay.asInt();
                return;
            }
        }
    }
}
