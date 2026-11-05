package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** 向下使用风弹并同时跳跃，完成后恢复槽位和跳跃意图。 */
public final class WindChargeModule extends GameModule {
    private final NumberValue aimSpeed = add(new NumberValue("Aim speed", 7, 1, 10, 0.1));
    private final BooleanValue silentAim = add(new BooleanValue("Silent aim", true));

    public WindChargeModule() {
        super("WindCharge", Category.PLAYER);
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        PlayerState state = state();
        if (bridge == null || actions == null || !state.present()) {
            return;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (!bridge.itemDescription(bridge.hotbarStack(slot)).contains("wind_charge")) {
                continue;
            }
            actions.selectSlot(slot, silentAim.get());
            float pitch = dev.nocturne.client.control.RotationController.approach(
                    state.pitch(), 90.0f, aimSpeed.asFloat());
            if (silentAim.get()) {
                actions.sendRotation(state.yaw(), pitch, state.onGround());
            } else {
                actions.setRotation(state.yaw(), pitch);
            }
            actions.setJumping(true);
            actions.useItem(false);
            actions.setJumping(false);
            actions.selectSlot(state.selectedSlot(), silentAim.get());
            setEnabled(false);
            return;
        }
        setEnabled(false);
    }
}
