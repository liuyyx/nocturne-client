package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 在两套材质盔甲之间进行真实三段槽位交换。 */
public final class ArmorSwitchModule extends GameModule {
    private final ModeValue first = add(new ModeValue(
            "Set 1", "Diamond", "Diamond", "Gold", "Iron", "Leather", "Chain", "Netherite"));
    private final ModeValue second = add(new ModeValue(
            "Set 2", "Gold", "Diamond", "Gold", "Iron", "Leather", "Chain", "Netherite"));
    private final NumberValue delay = add(new NumberValue("Delay", 100, 0, 200, 10));
    private int armorType;
    private long nextClick;

    public ArmorSwitchModule() {
        super("ArmorSwitch", Category.INVENTORY);
    }

    @Override
    protected void onStart() {
        armorType = 0;
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
        String current = InventorySupport.material(bridge, bridge.inventoryStack(39 - armorType));
        String target = current.equalsIgnoreCase(first.get()) ? second.get() : first.get();
        for (int slot = 9; slot <= 35; slot++) {
            Object stack = bridge.inventoryStack(slot);
            if (InventorySupport.armorType(bridge, stack) != armorType
                    || !target.equalsIgnoreCase(InventorySupport.material(bridge, stack))) {
                continue;
            }
            int equipment = 5 + armorType;
            actions.clickSlot(slot, 0, "pickup");
            actions.clickSlot(equipment, 0, "pickup");
            actions.clickSlot(slot, 0, "pickup");
            nextClick = now + delay.asInt();
            break;
        }
        armorType++;
    }
}
