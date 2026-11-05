package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** 清除攻击未命中产生的客户端点击延迟。 */
public final class NoClickDelayModule extends GameModule {
    public NoClickDelayModule() {
        super("NoClickDelay", Category.PLAYER);
    }

    @Override
    public void onTick() {
        if (bridge() != null) {
            bridge().writeField(bridge().minecraft(), ClassType.MINECRAFT, "missTime", 0);
        }
    }
}
