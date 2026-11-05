package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon Auto Totem：血量阈值、Gapple 检查与 Strict 双击/普通 swap。 */
public final class AutoTotemModule extends GameModule {
    private final BooleanValue strict = add(new BooleanValue("Strict", true));
    private final NumberValue health = add(new NumberValue("Health", 16, 0, 36, 0.5));
    private final BooleanValue checkGapple = add(new BooleanValue("Check Gapple", true));

    public AutoTotemModule() {
        super("Auto Totem", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (bridge().containerOpen() || !shouldHoldTotem()) {
            return;
        }
        int slot = EpsilonSupport.findInventory(bridge(), "totem_of_undying");
        if (slot < 0) {
            return;
        }
        if (strict.get()) {
            actions().clickSlot(slot, 0, "pickup");
            actions().clickSlot(40, 0, "pickup");
        } else {
            actions().clickSlot(slot, 40, "swap");
        }
    }

    private boolean shouldHoldTotem() {
        String offhand = bridge().itemDescription(bridge().heldItem(bridge().player(), true));
        if (offhand.contains("totem_of_undying")) {
            return false;
        }
        if (checkGapple.get() && offhand.contains("golden_apple")
                && state().health() > health.get() && state().y() >= -64.0) {
            return false;
        }
        return state().health() <= health.get() || offhand.isEmpty() || state().y() < -64.0;
    }
}
