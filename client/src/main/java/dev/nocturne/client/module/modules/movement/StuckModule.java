package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MotionEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** Stuck：锁定位置提交，防止被推离当前格。 */
public final class StuckModule extends GameModule {
    private double x;
    private double y;
    private double z;

    public StuckModule() {
        super("Stuck", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        x = state().x();
        y = state().y();
        z = state().z();
        listen(MotionEvent.class, event -> {
            if (event.pre) {
                event.x = x;
                event.y = y;
                event.z = z;
            }
        });
    }
}
