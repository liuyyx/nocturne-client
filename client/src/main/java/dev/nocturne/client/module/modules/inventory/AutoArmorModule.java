package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.NumberBounds;

import java.util.Random;

/** 比较四类盔甲的材质与耐久，并把更优装备移入对应装备槽。 */
public final class AutoArmorModule extends GameModule {
    private final BooleanValue checkDurability = add(new BooleanValue("Check durability", true));
    private final BooleanValue dropEquipped = add(new BooleanValue("Drop equipped", false));
    private final BoundsNumberValue delay = add(new BoundsNumberValue(
            "Delay", new NumberBounds(100, 120), 1, 200, 1));
    private final Random random = new Random();
    private int armorType;
    private long nextClick;

    public AutoArmorModule() {
        super("AutoArmor", Category.INVENTORY);
    }

    @Override
    protected void onStart() {
        armorType = 0;
        nextClick = 0L;
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
        if (armorType >= 4) {
            armorType = 0;
            return;
        }
        int currentSlot = 39 - armorType;
        Object current = bridge.inventoryStack(currentSlot);
        int bestSlot = -1;
        int bestScore = score(bridge, current);
        for (int slot = 9; slot <= 35; slot++) {
            Object candidate = bridge.inventoryStack(slot);
            if (InventorySupport.armorType(bridge, candidate) != armorType) {
                continue;
            }
            int score = score(bridge, candidate);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0) {
            int equipmentMenuSlot = 5 + armorType;
            if (dropEquipped.get() && !bridge.isEmptyStack(current)) {
                actions.clickSlot(equipmentMenuSlot, 0, "pickup");
                actions.clickSlot(-999, 0, "pickup");
            }
            actions.clickSlot(bestSlot, 0, "quick_move");
            NumberBounds bounds = delay.get();
            nextClick = now + (long) (bounds.min() + random.nextDouble() * (bounds.max() - bounds.min()));
        }
        armorType++;
    }

    private int score(GameBridge bridge, Object stack) {
        int score = InventorySupport.armorScore(bridge, stack);
        return checkDurability.get() ? score : score / 1000;
    }
}
