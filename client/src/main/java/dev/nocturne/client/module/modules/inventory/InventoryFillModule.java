package dev.nocturne.client.module.modules.inventory;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/** 容器打开且按住 Shift+左键时，以配置 CPS 连续转移物品。 */
public final class InventoryFillModule extends GameModule {
    private final NumberValue cps = add(new NumberValue("CPS", 15, 1, 20, 1));
    private boolean left;
    private boolean shift;
    private int slot;
    private long nextClick;

    public InventoryFillModule() {
        super("InventoryFill", Category.INVENTORY);
    }

    @Override
    protected void onStart() {
        listen(MouseButtonEvent.class, event -> {
            if (event.button == 0) {
                left = event.pressed;
            }
        });
        listen(KeyEvent.class, event -> {
            if (event.keyCode == 16) {
                shift = event.pressed;
            }
        });
    }

    @Override
    protected void onStop() {
        left = false;
        shift = false;
        slot = 0;
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (bridge == null || actions == null || !bridge.containerOpen() || !left || !shift) {
            slot = 0;
            return;
        }
        long now = System.nanoTime();
        if (now < nextClick) {
            return;
        }
        int count = Math.max(0, bridge.containerSize() - 36);
        for (int attempts = 0; attempts < count; attempts++) {
            int current = slot++ % Math.max(1, count);
            if (!bridge.isEmptyStack(bridge.containerStack(current))) {
                actions.clickSlot(current, 0, "quick_move");
                break;
            }
        }
        nextClick = now + (long) (1_000_000_000.0 / cps.get());
    }
}
