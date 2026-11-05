package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** Dolphin：水中持续给跳跃意图，让玩家顺水上浮。 */
public final class DolphinModule extends GameModule {
    public DolphinModule() {
        super("Dolphin", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        if (bridge() != null && actions() != null && bridge().inWater(bridge().player())) {
            actions().setJumping(true);
        }
    }
}
