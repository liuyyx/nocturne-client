package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** SafeCrystal：带距离、遮挡和自伤阈值的水晶破坏/放置。 */
public final class SafeCrystalModule extends GameModule {
    private final BooleanValue autoBreak = add(new BooleanValue("Auto Break", true));
    private final BooleanValue autoPlace = add(new BooleanValue("Auto Place", true));
    private final NumberValue antiSuicide = add(new NumberValue("Anti Suicide", 2.0, 1, 20, 0.1));

    public SafeCrystalModule() {
        super("Safe Crystal", Category.COMBAT);
    }

    @Override
    public void onTick() {
        Object hit = actions().pick(3.6, 1.0f);
        Object crystal = bridge().hitEntity(hit);
        if (autoBreak.get() && crystal != null && bridge().isInstance(crystal, ClassType.END_CRYSTAL)
                && state().health() > antiSuicide.get()) {
            actions().attack(crystal);
            actions().swing(false, false);
            return;
        }
        if (!autoPlace.get() || state().health() <= antiSuicide.get()) {
            return;
        }
        int[] pos = bridge().hitBlockPosition(hit);
        if (pos == null) {
            return;
        }
        String id = bridge().blockDescription(bridge().blockStateAt(pos[0], pos[1], pos[2]));
        if (!(id.contains("obsidian") || id.contains("bedrock"))
                || !bridge().isAir(bridge().blockStateAt(pos[0], pos[1] + 1, pos[2]))
                || !bridge().isAir(bridge().blockStateAt(pos[0], pos[1] + 2, pos[2]))) {
            return;
        }
        int slot = EpsilonSupport.findHotbar(bridge(), "end_crystal");
        if (slot >= 0) {
            int original = state().selectedSlot();
            actions().selectSlot(slot, true);
            actions().placeAt(pos[0], pos[1], pos[2], "up");
            actions().selectSlot(original, true);
        }
    }
}
