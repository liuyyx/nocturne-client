package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.NumberBounds;
import dev.nocturne.client.value.TextValue;

import java.util.Random;

/** 按原槽位顺序取走当前容器物品，取完后按设置关闭。 */
public final class ChestStealModule extends GameModule {
    private final BooleanValue keepOpen = add(new BooleanValue("Keep open", false));
    private final BoundsNumberValue clickDelay = add(new BoundsNumberValue(
            "Click delay", new NumberBounds(150, 200), 50, 300, 5));
    private final TextValue blacklisted = add(new TextValue("Blacklisted", "", 512));
    private final Random random = new Random();
    private int slot;
    private long nextClick;

    public ChestStealModule() {
        super("ChestSteal", Category.INVENTORY);
    }

    @Override
    protected void onStart() {
        slot = 0;
        nextClick = 0L;
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (bridge == null || actions == null || !bridge.containerOpen()) {
            slot = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextClick) {
            return;
        }
        int containerSlots = Math.max(0, bridge.containerSize() - 36);
        if (slot >= containerSlots) {
            if (!keepOpen.get()) {
                actions.closeContainer();
            }
            slot = 0;
            return;
        }
        Object stack = bridge.containerStack(slot);
        if (!bridge.isEmptyStack(stack) && !bridge.itemMatches(stack, blacklisted.get())) {
            actions.clickSlot(slot, 0, "quick_move");
        }
        slot++;
        NumberBounds delay = clickDelay.get();
        nextClick = now + (long) (delay.min() + random.nextDouble() * (delay.max() - delay.min()));
    }
}
