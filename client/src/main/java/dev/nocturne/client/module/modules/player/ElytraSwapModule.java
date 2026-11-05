package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.inventory.InventorySupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.value.NumberValue;

/** ElytraSwap：按 G 在胸甲与鞘翅间切换，支持背包移槽和换槽恢复。 */
public final class ElytraSwapModule extends GameModule {
    private final KeyValue activate = add(new KeyValue("Activate Key", 71));
    private final NumberValue delay = add(new NumberValue("Delay", 0, 0, 20, 1));
    private final BooleanValue switchBack = add(new BooleanValue("Switch Back", true));
    private final NumberValue switchDelay = add(new NumberValue("Switch Delay", 0, 0, 20, 1));
    private final BooleanValue moveToSlot = add(new BooleanValue("Move to slot", true));
    private final NumberValue elytraSlot = add(new NumberValue("Elytra Slot", 9, 1, 9, 1));
    private boolean held;
    private int stage;
    private int wait;
    private int original = -1;

    public ElytraSwapModule() {
        super("ElytraSwap", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(KeyEvent.class, event -> {
            if (event.keyCode == activate.get()) {
                held = event.pressed;
                if (!held) {
                    restore();
                }
            }
        });
    }

    @Override
    public void onTick() {
        if (!held) {
            return;
        }
        if (stage == 0) {
            boolean equipped = bridge().itemDescription(bridge().equipmentStack(bridge().player(), 2)).contains("elytra");
            int slot = EpsilonSupport.findHotbar(bridge(), equipped ? "chestplate" : "elytra");
            if (slot < 0 && moveToSlot.get()) {
                int inventory = EpsilonSupport.findInventory(bridge(), equipped ? "chestplate" : "elytra");
                if (inventory >= 9) {
                    slot = elytraSlot.asInt() - 1;
                    actions().clickSlot(InventorySupport.menuSlot(inventory), slot, "swap");
                }
            }
            if (slot < 0) {
                held = false;
                return;
            }
            original = state().selectedSlot();
            actions().selectSlot(slot, false);
            wait = delay.asInt();
            stage = 1;
        }
        if (stage == 1 && --wait <= 0) {
            actions().useItem();
            actions().swing(false, false);
            wait = switchDelay.asInt();
            stage = 2;
        } else if (stage == 2 && --wait <= 0) {
            restore();
            held = false;
        }
    }

    private void restore() {
        if (switchBack.get() && original >= 0 && actions() != null) {
            actions().selectSlot(original, false);
        }
        original = -1;
        stage = 0;
    }

    @Override
    protected void onStop() {
        held = false;
        restore();
    }
}
