package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Hold/Toggle 的观察朝向控制，服务器视角与真实人称状态在退出时恢复。 */
public final class FreeLookModule extends GameModule {
    private final ModeValue activationMode = add(new ModeValue(
            "Activate Freelook", "Hold", "Hold", "Toggle"));
    private final ModeValue startingPosition = add(new ModeValue(
            "Starting position", "Forward", "Forward", "Backward"));
    private final ModeValue perspectiveMode = add(new ModeValue(
            "Perspective", "3rd Person", "3rd Person", "1st Person"));
    private final BooleanValue customSensitivity = add(new BooleanValue("Use Custom Sensitivity", false));
    private final NumberValue sensitivity = add(new NumberValue("Sensitivity", 0.5, 0.1, 2.0, 0.1));
    private boolean active;
    private float originalYaw;
    private float originalPitch;
    private int originalPerspective;
    private float previousYaw;
    private float previousPitch;

    public FreeLookModule() {
        super("Freelook", Category.RENDER);
    }

    @Override
    protected void onStart() {
        listen(KeyEvent.class, this::onKey);
    }

    private void onKey(KeyEvent event) {
        if (!bind().bound() || event.keyCode != bind().get()) {
            return;
        }
        if (activationMode.is("Toggle") && event.pressed) {
            setActive(!active);
        } else if (activationMode.is("Hold")) {
            setActive(event.pressed);
        }
    }

    private void setActive(boolean value) {
        if (active == value) {
            return;
        }
        PlayerState state = state();
        if (!active) {
            active = true;
            originalYaw = state.yaw();
            originalPitch = state.pitch();
            originalPerspective = bridge().perspective();
            previousYaw = originalYaw;
            previousPitch = originalPitch;
            bridge().setPerspective(perspectiveMode.is("1st Person") ? 0 : 1);
            if (startingPosition.is("Backward") && actions() != null) {
                actions().setRotation(RotationController.wrap(originalYaw + 180.0f), originalPitch);
            }
        } else {
            restore();
        }
    }

    @Override
    public void onTick() {
        if (!active) {
            return;
        }
        PlayerState state = state();
        ActionApi actions = actions();
        if (!state.present() || actions == null) {
            setActive(false);
            return;
        }
        if (customSensitivity.get()) {
            float yaw = previousYaw + RotationController.wrap(state.yaw() - previousYaw)
                    * sensitivity.asFloat();
            float pitch = previousPitch + (state.pitch() - previousPitch) * sensitivity.asFloat();
            actions.setRotation(yaw, pitch);
            previousYaw = yaw;
            previousPitch = pitch;
        } else {
            previousYaw = state.yaw();
            previousPitch = state.pitch();
        }
        actions.sendRotation(originalYaw, originalPitch, state.onGround());
    }

    @Override
    protected void onStop() {
        restore();
    }

    private void restore() {
        if (!active) {
            return;
        }
        if (bridge() != null && bridge().player() != null) {
            bridge().setPerspective(originalPerspective);
            if (actions() != null) {
                actions().setRotation(originalYaw, originalPitch);
            }
        }
        active = false;
    }
}
