package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberBounds;

import java.util.ArrayDeque;
import java.util.Random;

/** 一次性使用热栏中的治疗药水或汤，结束后恢复原槽。 */
public final class ThrowpotModule extends GameModule {
    private final ModeValue type = add(new ModeValue("Type", "Both", "Both", "Pots", "Soup"));
    private final ModeValue mode = add(new ModeValue("Mode", "Dynamic", "Dynamic", "Single"));
    private final BoundsNumberValue delay = add(new BoundsNumberValue(
            "Delay", new NumberBounds(80, 115), 0, 200, 1));
    private final ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
    private final Random random = new Random();
    private int savedSlot;
    private long nextUse;

    public ThrowpotModule() {
        super("Throwpot", Category.INVENTORY);
    }

    @Override
    protected void onStart() {
        queue.clear();
        savedSlot = state().selectedSlot();
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        for (int slot = 0; slot <= 8; slot++) {
            String id = bridge.itemDescription(bridge.hotbarStack(slot));
            boolean soup = id.contains("soup") || id.contains("stew");
            if (!(soup || id.contains("splash_potion"))
                    || type.is("Pots") && soup || type.is("Soup") && !soup) {
                continue;
            }
            queue.add(slot);
            if (mode.is("Single")) {
                break;
            }
        }
    }

    @Override
    public void onTick() {
        ActionApi actions = actions();
        if (actions == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextUse) {
            return;
        }
        Integer slot = queue.poll();
        if (slot == null) {
            actions.selectSlot(savedSlot, false);
            setEnabled(false);
            return;
        }
        actions.selectSlot(slot, false);
        actions.useItem(false);
        NumberBounds bounds = delay.get();
        nextUse = now + (long) (bounds.min() + random.nextDouble() * (bounds.max() - bounds.min()));
    }

    @Override
    protected void onStop() {
        ActionApi actions = actions();
        if (actions != null && savedSlot >= 0) {
            actions.selectSlot(savedSlot, false);
        }
        queue.clear();
    }
}
