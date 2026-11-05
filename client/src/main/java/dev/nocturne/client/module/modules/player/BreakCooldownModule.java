package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.NumberValue;

/** BreakCooldown：把挖掘冷却写为配置 tick。 */
public final class BreakCooldownModule extends GameModule {
    private final NumberValue cooldown = add(new NumberValue("Cooldown", 0, 0, 5, 1));

    public BreakCooldownModule() {
        super("BreakCooldown", Category.PLAYER);
    }

    @Override
    public void onTick() {
        bridge().writeField(EpsilonSupport.gameMode(bridge()), ClassType.MULTI_PLAYER_GAME_MODE,
                "destroyDelay", cooldown.asInt());
    }
}
