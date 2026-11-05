package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** 仅当前进路径下沿即将失去碰撞支撑时触发一次跳跃。 */
public final class ParkourModule extends GameModule {
    public ParkourModule() {
        super("Parkour", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, this::onInput);
    }

    private void onInput(MovementInputEvent event) {
        PlayerState state = state();
        GameBridge bridge = bridge();
        if (event.jump || event.forward <= 0.0f || !state.present() || !state.onGround() || bridge == null) {
            return;
        }
        Object box = bridge.callMapped(state.player(), ClassType.ENTITY, "getBoundingBox");
        double radians = Math.toRadians(state.yaw());
        double x = -Math.sin(radians);
        double z = Math.cos(radians);
        Object currentEdge = bridge.moveBox(box, 0.0, -0.1, 0.0);
        Object nextEdge = bridge.moveBox(box, x, -0.1, z);
        if (bridge.noCollision(state.player(), currentEdge)
                && bridge.noCollision(state.player(), nextEdge)) {
            event.jump = true;
        }
    }
}
