package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** HoverTotem：背包悬停图腾时交换到指定热栏槽或副手。 */
public final class HoverTotemModule extends GameModule {
    private final NumberValue delay = add(new NumberValue("Delay", 0, 0, 20, 0.1));
    private final NumberValue randomDelay = add(new NumberValue("Random Delay", 0, 0, 10, 0.1));
    private final BooleanValue hotbar = add(new BooleanValue("Hotbar", true));
    private final NumberValue totemSlot = add(new NumberValue("Totem Slot", 1, 1, 9, 1));
    private final BooleanValue autoSwitch = add(new BooleanValue("Auto Switch", true));
    private int clock;

    public HoverTotemModule() {
        super("Hover Totem", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (!bridge().isInstance(bridge().currentScreen(), ClassType.INVENTORY_SCREEN)) {
            clock = nextDelay();
            return;
        }
        int slot = totemSlot.asInt() - 1;
        if (autoSwitch.get()) {
            actions().selectSlot(slot, false);
        }
        int hovered = bridge().hoveredSlotIndex();
        if (hovered < 0 || hovered > 35
                || !bridge().itemDescription(bridge().containerStack(hovered)).contains("totem_of_undying")) {
            return;
        }
        if (clock-- > 0) {
            return;
        }
        if (hotbar.get() && !bridge().itemDescription(bridge().hotbarStack(slot)).contains("totem_of_undying")) {
            actions().clickSlot(hovered, slot, "swap");
        } else if (!hotbar.get() && !bridge().itemDescription(
                bridge().heldItem(bridge().player(), true)).contains("totem_of_undying")) {
            actions().clickSlot(hovered, 40, "swap");
        }
        clock = nextDelay();
    }

    private int nextDelay() {
        return (int) Math.ceil(delay.get() + dev.nocturne.client.module.modules.EpsilonSupport.RANDOM.nextDouble()
                * randomDelay.get());
    }
}
