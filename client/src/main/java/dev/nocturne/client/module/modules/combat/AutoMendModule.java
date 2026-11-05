package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;

/** AutoMend：装备存在可修补损耗时，每四 tick 使用一瓶经验瓶。 */
public final class AutoMendModule extends GameModule {
    private final ModeValue switchMode = add(new ModeValue("Switch Mode", "Normal", "Normal", "Silent"));
    private final BooleanValue swingHand = add(new BooleanValue("Swing Hand", false));
    private int delay;
    private int original = -1;

    public AutoMendModule() {
        super("AutoMend", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (original >= 0) {
            actions().selectSlot(original, switchMode.is("Silent"));
            original = -1;
        }
        if (++delay < 4 || !needsMending()) {
            return;
        }
        delay = 0;
        int slot = EpsilonSupport.findHotbar(bridge(), "experience_bottle", "xp_bottle");
        if (slot < 0) {
            return;
        }
        original = state().selectedSlot();
        if (original == slot) {
            original = -1;
        } else {
            actions().selectSlot(slot, switchMode.is("Silent"));
        }
        client().rotations().request(state().yaw(), 90.0f, 5, 150_000_000L);
        if (actions().useItem()) {
            actions().swing(false, !swingHand.get());
        }
    }

    private boolean needsMending() {
        for (int slot = 0; slot < 9; slot++) {
            if (damaged(bridge().hotbarStack(slot))) {
                return true;
            }
        }
        for (int slot = 36; slot < 40; slot++) {
            if (damaged(bridge().inventoryStack(slot))) {
                return true;
            }
        }
        return damaged(bridge().heldItem(bridge().player(), true));
    }

    private boolean damaged(Object stack) {
        return !bridge().isEmptyStack(stack) && bridge().itemMaxDamage(stack) > 0
                && bridge().itemDamage(stack) > 0;
    }

    @Override
    protected void onStop() {
        if (original >= 0 && actions() != null) {
            actions().selectSlot(original, switchMode.is("Silent"));
        }
        original = -1;
        delay = 0;
    }
}
