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
    private final NumberValue horizontalSpeed = add(new NumberValue("Horizontal Speed", 3.5, 0.1, 10.0, 0.1));
    private final NumberValue verticalSpeed = add(new NumberValue("Vertical Speed", 1.0, 0.1, 10.0, 0.1));
    private float forward;
    private float strafe;
    private boolean jump;
    private boolean sneak;

    public FlyModule() {
        super("Flight", Category.MOVEMENT);
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
        double[] horizontal = moveVector(forward, strafe, state.yaw(), horizontalSpeed.get());
        event.x = horizontal[0];
        event.z = horizontal[1];
        event.y = jump ? verticalSpeed.get() : sneak ? -verticalSpeed.get() : 0.0;
        ActionApi actions = actions();
        if (actions != null) {
            actions.setMotion(event.x, event.y, event.z);
        }
    }

    /**
     * 输入 → 世界水平位移。
     *
     * <p>原版输入向量可能超过 1（斜向同时按 W+D 是 √2），不归一化会让斜向速度变成 1.41 倍。
     * 超过 1 时按长度缩放到 {@code speed}，不足 1 时保持比例（半推摇杆仍然是半速）。
     */
    static double[] moveVector(float forward, float strafe, double yawDegrees, double speed) {
        double length = Math.hypot(forward, strafe);
        double scale = length > 1.0 ? speed / length : speed;
        double radians = Math.toRadians(yawDegrees);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        return new double[]{(strafe * cos - forward * sin) * scale,
                (forward * cos + strafe * sin) * scale};
    }
}
