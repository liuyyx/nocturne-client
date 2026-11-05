package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ModeValue;

/** Bhop/AntiCheat B 两种位移速度控制器。 */
public final class SpeedModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Bhop", "Bhop", "AntiCheat B"));

    public SpeedModule() {
        super("Speed", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MoveEvent.class, this::onMove);
    }

    private void onMove(MoveEvent event) {
        PlayerState state = state();
        if (!state.present() || !state.moving()) {
            return;
        }
        double horizontal = Math.sqrt(event.x * event.x + event.z * event.z);
        if (horizontal < 1.0e-5) {
            return;
        }
        if (state.onGround()) {
            event.y = 0.42;
        }
        double speed = mode.is("AntiCheat B") ? 0.42 : 0.62;
        event.x = event.x / horizontal * speed;
        event.z = event.z / horizontal * speed;
        ActionApi actions = actions();
        if (actions != null) {
            actions.setMotion(event.x, event.y, event.z);
        }
    }
}
