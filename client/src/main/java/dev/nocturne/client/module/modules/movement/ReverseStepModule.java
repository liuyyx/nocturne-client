package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** ReverseStep：下落初期增大向下位移，快速离开台阶边缘。 */
public final class ReverseStepModule extends GameModule {
    private final NumberValue fallSpeed = add(new NumberValue("Fall Speed", 3.0, 0.0, 10.0, 0.1));
    private final NumberValue fallDistance = add(new NumberValue("Fall Distance", 3.0, 0.0, 10.0, 0.1));
    private final BooleanValue vehicles = add(new BooleanValue("Vehicles", false));

    public ReverseStepModule() {
        super("ReverseStep", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MoveEvent.class, event -> {
            if (!vehicles.get() && Boolean.TRUE.equals(bridge().callMapped(
                    bridge().player(), dev.nocturne.client.mapping.ClassType.ENTITY, "isPassenger"))) {
                return;
            }
            float distance = bridge().fallDistance(bridge().player());
            if (event.y < 0.0 && distance >= fallDistance.get() && distance < fallDistance.get() + 1.0) {
                event.y -= fallSpeed.get() * 0.1;
            }
        });
    }
}
