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
        if (bridge().containerOpen() || !offhandExists() || !shouldHoldTotem()) {
            return;
        }
        int slot = EpsilonSupport.findInventory(bridge(), "totem_of_undying");
        if (slot < 0) {
            return;
        }
        int source = InventorySupport.menuSlot(slot);
        int offhand = InventorySupport.menuSlot(InventorySupport.OFFHAND);
        if (strict.get()) {
            actions().clickSlot(source, 0, "pickup");
            actions().clickSlot(offhand, 0, "pickup");
            if (!holdsTotem()) {
                // 副手点击失败（例如服务端拒绝）时把光标上的图腾放回原槽，绝不留在光标上。
                actions().clickSlot(source, 0, "pickup");
            }
        } else {
            actions().clickSlot(source, 0, "pickup");
            actions().clickSlot(offhand, 0, "pickup");
        }
    }

    /**
     * 玩家背包菜单是否有副手槽。
     *
     * <p>1.8.9 的玩家菜单只有 45 格（0..44），第 45 槽不存在；读不到槽数时按"没有副手"处理，
     * 以免每 tick 点击不存在的槽污染光标。
     */
    private boolean offhandExists() {
        return bridge().containerSize() > InventorySupport.menuSlot(InventorySupport.OFFHAND);
    }

    private boolean holdsTotem() {
        return bridge().itemDescription(bridge().heldItem(bridge().player(), true))
                .contains("totem_of_undying");
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
