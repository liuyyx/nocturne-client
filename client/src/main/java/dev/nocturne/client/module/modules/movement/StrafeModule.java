package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;

/** Strafe：保留空中水平速度，并按输入状态执行自动跳跃/空中停止。 */
public final class StrafeModule extends GameModule {
    private final BooleanValue airStop = add(new BooleanValue("Air Stop", true));
    private final BooleanValue autoJump = add(new BooleanValue("Auto Jump", true));

    public StrafeModule() {
        super("Strafe", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MoveEvent.class, event -> {
            if (airStop.get() && !state().onGround() && !state().moving()) {
                event.x = 0.0;
                event.z = 0.0;
            }
        });
    }

    @Override
    public void onTick() {
        if (autoJump.get() && state().onGround() && state().moving() && actions() != null) {
            actions().setJumping(true);
        }
    }
}
