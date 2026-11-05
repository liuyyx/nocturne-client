package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 先抛珍珠，等待指定 tick 后用风弹追向同一方向。 */
public final class PearlCatchModule extends GameModule {
    private final ModeValue aimMode = add(new ModeValue("Aim mode", "Upward", "Upward", "Current aim"));
    private final NumberValue aimSpeed = add(new NumberValue("Aim speed", 10, 1, 10, 0.1));
    private final BooleanValue silentAim = add(new BooleanValue("Silent aim", true));
    private final NumberValue chargeDelay = add(new NumberValue("Charge delay", 0, 0, 10, 1));
    private int stage;
    private int wait;
    private int savedSlot;
    private float yaw;
    private float pitch;

    public PearlCatchModule() {
        super("PearlCatch", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        stage = 0;
        savedSlot = state().selectedSlot();
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        PlayerState state = state();
        if (bridge == null || actions == null || !state.present()) {
            return;
        }
        if (stage == 1) {
            if (--wait <= 0) {
                stage = 2;
            }
            return;
        }
        float targetYaw = stage == 0 || aimMode.is("Upward") ? state.yaw() : state.yaw();
        float targetPitch = aimMode.is("Upward") ? -90.0f : state.pitch();
        yaw = RotationController.approach(state.yaw(), targetYaw, aimSpeed.asFloat());
        pitch = RotationController.approach(state.pitch(), targetPitch, aimSpeed.asFloat());
        if (silentAim.get()) {
            actions.sendRotation(yaw, pitch, state.onGround());
        } else {
            actions.setRotation(yaw, pitch);
        }
        if (Math.abs(RotationController.wrap(targetYaw - yaw)) > 0.5f
                || Math.abs(targetPitch - pitch) > 0.5f) {
            return;
        }
        String wanted = stage == 0 ? "ender_pearl" : "wind_charge";
        for (int slot = 0; slot <= 8; slot++) {
            if (!bridge.itemDescription(bridge.hotbarStack(slot)).contains(wanted)) {
                continue;
            }
            actions.selectSlot(slot, silentAim.get());
            actions.useItem(false);
            if (stage == 0) {
                wait = chargeDelay.asInt() + 1;
                stage = 1;
            } else {
                actions.selectSlot(savedSlot, silentAim.get());
                setEnabled(false);
            }
            return;
        }
        setEnabled(false);
    }

    @Override
    protected void onStop() {
        if (savedSlot >= 0 && actions() != null) {
            actions().selectSlot(savedSlot, silentAim.get());
        }
        stage = 0;
    }
}
