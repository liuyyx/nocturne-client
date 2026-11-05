package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberBounds;
import dev.nocturne.client.value.TextValue;

import java.util.Random;

/** 组合垃圾清理与基础热栏整理的 InventoryManager。 */
public final class InventoryManagerModule extends GameModule {
    private final ModeValue profile = add(new ModeValue("Profile", "Both", "Both", "Clean", "Organize"));
    private final BoundsNumberValue clickDelay = add(new BoundsNumberValue(
            "Click delay", new NumberBounds(100, 150), 50, 300, 5));
    private final TextValue remove = add(new TextValue("Remove", "string,bone,compass,feather,glass_bottle", 512));
    private final Random random = new Random();
    private long nextClick;

    public InventoryManagerModule() {
        super("InventoryManager", Category.INVENTORY);
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
        boolean acted = !profile.is("Organize") && cleanOne(bridge, actions)
                || !profile.is("Clean") && organizeOne(bridge, actions);
        if (acted) {
            NumberBounds bounds = clickDelay.get();
            nextClick = now + (long) (bounds.min() + random.nextDouble() * (bounds.max() - bounds.min()));
        }
    }

    private boolean cleanOne(GameBridge bridge, ActionApi actions) {
        for (int slot = 9; slot <= 44; slot++) {
            if (bridge.itemMatches(bridge.containerStack(slot), remove.get())) {
                actions.clickSlot(slot, 0, "pickup");
                actions.clickSlot(-999, 0, "pickup");
                return true;
            }
        }
        return false;
    }

    private boolean organizeOne(GameBridge bridge, ActionApi actions) {
        String[] rules = {"sword", "pickaxe", "blocks"};
        int[] targets = {0, 1, 7};
        for (int i = 0; i < rules.length; i++) {
            if (bridge.itemMatches(bridge.inventoryStack(targets[i]), rules[i])) {
                continue;
            }
            for (int source = 9; source <= 35; source++) {
                if (bridge.itemMatches(bridge.inventoryStack(source), rules[i])) {
                    actions.clickSlot(source, targets[i], "swap");
                    return true;
                }
            }
        }
        return false;
    }
}
