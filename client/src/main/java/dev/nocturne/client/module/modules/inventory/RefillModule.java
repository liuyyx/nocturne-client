package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.NumberBounds;
import dev.nocturne.client.value.ModeValue;

import java.util.Random;

/** 将背包中的治疗物品补入空热栏，必要时三段交换被占用槽。 */
public final class RefillModule extends GameModule {
    private final ModeValue type = add(new ModeValue("Type", "Both", "Both", "Pots", "Soup"));
    private final BooleanValue hotbarClear = add(new BooleanValue("Hotbar clear", false));
    private final BoundsNumberValue delay = add(new BoundsNumberValue(
            "Delay", new NumberBounds(75, 125), 50, 200, 5));
    private final Random random = new Random();
    private long nextClick;

    public RefillModule() {
        super("Refill", Category.INVENTORY);
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
        int target = emptyHotbar(bridge);
        if (target < 0) {
            return;
        }
        for (int source = 9; source <= 35; source++) {
            Object stack = bridge.inventoryStack(source);
            if (!wanted(bridge, stack)) {
                continue;
            }
            int menuTarget = InventorySupport.menuSlot(target);
            if (bridge.isEmptyStack(bridge.inventoryStack(target))) {
                actions.clickSlot(source, 0, "quick_move");
            } else if (hotbarClear.get()) {
                actions.clickSlot(source, 0, "pickup");
                actions.clickSlot(menuTarget, 0, "pickup");
                actions.clickSlot(source, 0, "pickup");
            }
            NumberBounds bounds = delay.get();
            nextClick = now + (long) (bounds.min() + random.nextDouble() * (bounds.max() - bounds.min()));
            return;
        }
    }

    private int emptyHotbar(GameBridge bridge) {
        for (int slot = 0; slot <= 8; slot++) {
            if (bridge.isEmptyStack(bridge.inventoryStack(slot))
                    || hotbarClear.get() && !InventorySupport.healing(bridge, bridge.inventoryStack(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private boolean wanted(GameBridge bridge, Object stack) {
        if (!InventorySupport.healing(bridge, stack)) {
            return false;
        }
        String id = bridge.itemDescription(stack);
        return type.is("Both") || type.is("Pots") && id.contains("potion")
                || type.is("Soup") && (id.contains("soup") || id.contains("stew"));
    }
}
