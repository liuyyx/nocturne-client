package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Stealer：随机槽序 quick-move 有用物品，空箱按延迟自动关闭。 */
public final class ChestStealModule extends GameModule {
    private final NumberValue minDelay = add(new NumberValue("Min Delay", 110, 0, 1000, 50));
    private final NumberValue maxDelay = add(new NumberValue("Max Delay", 140, 0, 1000, 50));
    private final BooleanValue autoClose = add(new BooleanValue("Auto Close", true));
    private final NumberValue closeDelay = add(new NumberValue("Close Delay", 100, 0, 1000, 10));
    private final BooleanValue enderChest = add(new BooleanValue("Ender Chest", false));
    private final List<Integer> order = new ArrayList<Integer>();
    private long nextClick;
    private long emptySince;

    public ChestStealModule() {
        super("Stealer", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        order.clear();
        emptySince = 0L;
        nextClick = 0L;
    }

    @Override
    public void onTick() {
        if (!bridge().containerOpen()) {
            order.clear();
            emptySince = 0L;
            return;
        }
        if (!enderChest.get() && bridge().screenTitle().toLowerCase(java.util.Locale.ROOT).contains("ender")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextClick) {
            return;
        }
        int size = Math.max(0, bridge().containerSize() - 36);
        if (order.size() != size) {
            order.clear();
            for (int slot = 0; slot < size; slot++) {
                order.add(slot);
            }
            Collections.shuffle(order, EpsilonSupport.RANDOM);
        }
        while (!order.isEmpty()) {
            int slot = order.remove(0);
            Object stack = bridge().containerStack(slot);
            if (useful(stack)) {
                actions().clickSlot(slot, 0, "quick_move");
                nextClick = now + EpsilonSupport.randomLong(minDelay.asInt(), maxDelay.asInt());
                emptySince = 0L;
                return;
            }
        }
        if (emptySince == 0L) {
            emptySince = now;
        }
        if (autoClose.get() && now - emptySince >= closeDelay.asInt()) {
            actions().closeContainer();
            emptySince = 0L;
        }
    }

    private boolean useful(Object stack) {
        if (bridge().isEmptyStack(stack)) {
            return false;
        }
        String id = bridge().itemDescription(stack);
        return !id.matches(".*(dirt|gravel|cobblestone|rotten_flesh|poisonous_potato).*");
    }

    public boolean working() {
        return isEnabled() && bridge().containerOpen();
    }
}
