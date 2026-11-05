package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** 清除头顶碰撞产生的跳跃冷却。 */
public final class NoJumpDelayModule extends GameModule {
    public NoJumpDelayModule() {
        super("NoJumpDelay", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        if (bridge() != null) {
            bridge().writeField(bridge().player(), ClassType.LIVING_ENTITY, "jumpTicks", 0);
        }
    }
}
