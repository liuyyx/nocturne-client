package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** MovementFix：把实际位移从客户端朝向旋转到当前托管朝向。 */
public final class MovementFixModule extends GameModule {
    public MovementFixModule() {
        super("MovementFix", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MoveEvent.class, event -> {
            if (client() == null || !client().rotations().active()) {
                return;
            }
            float from = state().yaw();
            float to = client().rotations().yaw(from);
            double angle = Math.toRadians(to - from);
            double sin = Math.sin(angle);
            double cos = Math.cos(angle);
            double x = event.x * cos - event.z * sin;
            double z = event.x * sin + event.z * cos;
            event.x = x;
            event.z = z;
        });
    }
}
