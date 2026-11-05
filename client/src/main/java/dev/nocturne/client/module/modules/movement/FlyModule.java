package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/** 通过实际位移事件实现水平和垂直飞行。 */
public final class FlyModule extends GameModule {
    private final NumberValue speed = add(new NumberValue("Speed", 0.5, 0.1, 5.0, 0.1));
    private final NumberValue verticalSpeed = add(new NumberValue("Vertical Speed", 0.2, 0.1, 5.0, 0.1));
    private float forward;
    private float strafe;
    private boolean jump;
    private boolean sneak;

    public FlyModule() {
        super("Fly", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, event -> {
            forward = event.forward;
            strafe = event.strafe;
            jump = event.jump;
            sneak = event.sneak;
        });
        listen(MoveEvent.class, this::onMove);
    }

    @Override
    protected void onStop() {
        forward = 0.0f;
        strafe = 0.0f;
        jump = false;
        sneak = false;
    }

    @Override
    public void onTick() {
        PlayerState state = state();
        if (state.present()) {
            bridge().writeField(state.player(), ClassType.ENTITY, "onGround", false);
        }
    }

    private void onMove(MoveEvent event) {
        PlayerState state = state();
        if (!state.present()) {
            return;
        }
        double radians = Math.toRadians(state.yaw());
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        double magnitude = speed.get();
        event.x = (strafe * cos - forward * sin) * magnitude;
        event.z = (forward * cos + strafe * sin) * magnitude;
        event.y = jump ? verticalSpeed.get() : sneak ? -verticalSpeed.get() : 0.0;
        ActionApi actions = actions();
        if (actions != null) {
            actions.setMotion(event.x, event.y, event.z);
        }
    }
}
