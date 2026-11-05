package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** 对举盾目标切斧攻击，可追加一次攻击并自动切回原槽。 */
public final class ShieldBreakerModule extends GameModule {
    private final NumberValue swapDelay = add(new NumberValue("Swap delay", 5, 0, 20, 1));
    private final BooleanValue autoSwapBack = add(new BooleanValue("Auto swap back", true));
    private final NumberValue swapBackDelay = add(new NumberValue("Swap back delay", 5, 0, 20, 1));
    private final BooleanValue doubleClick = add(new BooleanValue("Double click", false));
    private int original = -1;
    private int ticks;
    private Object target;
    private boolean attacked;

    public ShieldBreakerModule() {
        super("ShieldBreaker", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, this::onAttack);
    }

    private void onAttack(AttackEvent event) {
        GameBridge bridge = bridge();
        if (event.post || original >= 0 || !bridge.usingItem(event.target)) {
            return;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (!bridge.itemMatches(bridge.hotbarStack(slot), "axe")) {
                continue;
            }
            original = state().selectedSlot();
            actions().selectSlot(slot, true);
            ticks = swapDelay.asInt();
            target = event.target;
            attacked = false;
            return;
        }
    }

    @Override
    public void onTick() {
        if (original < 0 || --ticks > 0) {
            return;
        }
        ActionApi actions = actions();
        if (!attacked) {
            attacked = true;
            if (actions != null && doubleClick.get() && target != null) {
                actions.attack(target);
            }
            if (autoSwapBack.get() && swapBackDelay.asInt() > 0) {
                ticks = swapBackDelay.asInt();
                return;
            }
        }
        if (autoSwapBack.get()) {
            actions.selectSlot(original, true);
        }
        original = -1;
        target = null;
    }

    @Override
    protected void onStop() {
        original = -1;
        target = null;
    }
}
