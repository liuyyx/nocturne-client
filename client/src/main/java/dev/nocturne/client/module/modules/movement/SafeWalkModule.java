package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;

/** 在移动输入层启用边缘保护，不写持久按键状态。 */
public final class SafeWalkModule extends GameModule {
    private final BooleanValue directionCheck = add(new BooleanValue("Direction Check", true));

    public SafeWalkModule() {
        super("SafeWalk", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, this::onInput);
    }

    private void onInput(MovementInputEvent event) {
        if (!directionCheck.get()
                || !(event.forward > 0.0f && event.strafe == 0.0f && !event.sneak)) {
            event.sneak = true;
        }
    }
}
