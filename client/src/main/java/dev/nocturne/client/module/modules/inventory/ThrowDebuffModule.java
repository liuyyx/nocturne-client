package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberBounds;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** 投掷热栏中的负面喷溅药水，支持全部、每种一个或仅首个。 */
public final class ThrowDebuffModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "All", "All", "One of each", "First"));
    private final BoundsNumberValue delay = add(new BoundsNumberValue(
            "Delay", new NumberBounds(70, 120), 0, 200, 1));
    private final ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
    private final Random random = new Random();
    private int savedSlot;
    private long nextUse;

    public ThrowDebuffModule() {
        super("ThrowDebuff", Category.INVENTORY);
    }

    @Override
    protected void onStart() {
        queue.clear();
        savedSlot = state().selectedSlot();
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Set<String> seen = new HashSet<String>();
        for (int slot = 0; slot <= 8; slot++) {
            Object stack = bridge.hotbarStack(slot);
            String id = bridge.itemDescription(stack);
            if (!id.contains("splash_potion") || !InventorySupport.harmful(bridge, stack)) {
                continue;
            }
            String kind = InventorySupport.harmfulKind(bridge, stack);
            if (mode.is("One of each") && !seen.add(kind)) {
                continue;
            }
            queue.add(slot);
            if (mode.is("First")) {
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
