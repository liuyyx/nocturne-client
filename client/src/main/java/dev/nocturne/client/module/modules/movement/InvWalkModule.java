package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;

/** 在原生背包/容器界面打开时直接写入真实移动输入。 */
public final class InvWalkModule extends GameModule {
    private final BooleanValue inventoryOnly = add(new BooleanValue("Inventory Only", false));
    private final BooleanValue sneak = add(new BooleanValue("Sneak", false));
    private final BooleanValue rotate = add(new BooleanValue("Rotate", false));
    private boolean forward;
    private boolean backward;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean shift;

    public InvWalkModule() {
        super("GUI Move", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(KeyEvent.class, this::onKey);
    }

    private void onKey(KeyEvent event) {
        switch (event.keyCode) {
            case 87: forward = event.pressed; break;
            case 83: backward = event.pressed; break;
            case 65: left = event.pressed; break;
            case 68: right = event.pressed; break;
            case 32: jump = event.pressed; break;
            case 16: shift = event.pressed; break;
            default: break;
        }
        if (rotate.get() && event.pressed) {
            float yaw = state().yaw();
            float pitch = state().pitch();
            if (event.keyCode == 37) yaw -= 5.0f;
            if (event.keyCode == 39) yaw += 5.0f;
            if (event.keyCode == 38) pitch -= 3.0f;
            if (event.keyCode == 40) pitch += 3.0f;
            if (actions() != null) {
                actions().setRotation(yaw, RotationController.clampPitch(pitch));
            }
        }
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null || bridge.player() == null || bridge.currentScreen() == null) {
            return;
        }
        Object screen = bridge.currentScreen();
        if (inventoryOnly.get() && !bridge.isInstance(screen, ClassType.INVENTORY_SCREEN)
                && !bridge.isInstance(screen, ClassType.PAUSE_SCREEN)) {
            return;
        }
        float forwardAmount = (forward ? 1.0f : 0.0f) - (backward ? 1.0f : 0.0f);
        float strafeAmount = (left ? 1.0f : 0.0f) - (right ? 1.0f : 0.0f);
        bridge.applyMovementInput(bridge.movementInput(), forwardAmount, strafeAmount,
                jump, sneak.get() && shift, forwardAmount != 0.0f);
    }

    @Override
    protected void onStop() {
        GameBridge bridge = bridge();
        if (bridge != null && bridge.movementInput() != null) {
            bridge.applyMovementInput(bridge.movementInput(), 0.0f, 0.0f, false, false, false);
        }
        forward = backward = left = right = jump = shift = false;
    }
}
