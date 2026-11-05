package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.TextValue;

/** 使用物品时恢复被原版压到 0.2 的移动输入和位移。 */
public final class NoSlowdownModule extends GameModule {
    private final BooleanValue limitItems = add(new BooleanValue("Limit Items", false));
    private final TextValue whitelisted = add(new TextValue("Whitelisted", "swords", 256));

    public NoSlowdownModule() {
        super("NoSlowdown", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, this::onInput);
        listen(MoveEvent.class, this::onMove);
    }

    private void onInput(MovementInputEvent event) {
        if (!usingItem()) {
            return;
        }
        if (event.forward != 0.0f) {
            event.forward = Math.signum(event.forward);
        }
        if (event.strafe != 0.0f) {
            event.strafe = Math.signum(event.strafe);
        }
    }

    private void onMove(MoveEvent event) {
        if (!usingItem()) {
            return;
        }
        double length = Math.sqrt(event.x * event.x + event.z * event.z);
        if (length > 1.0e-5 && length <= 0.21) {
            event.x *= 5.0;
            event.z *= 5.0;
        }
    }

    private boolean usingItem() {
        PlayerState state = state();
        GameBridge bridge = bridge();
        if (!state.present() || bridge == null || !Boolean.TRUE.equals(
                bridge.callMapped(state.player(), ClassType.LIVING_ENTITY, "isUsingItem"))) {
            return false;
        }
        return !limitItems.get()
                || bridge.itemMatches(bridge.heldItem(state.player(), false), whitelisted.get());
    }
}
