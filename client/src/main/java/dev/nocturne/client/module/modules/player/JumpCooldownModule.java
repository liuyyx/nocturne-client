package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/** JumpCooldown：把原生跳跃冷却写为配置 tick。 */
public final class JumpCooldownModule extends GameModule {
    private final NumberValue cooldown = add(new NumberValue("Cooldown", 0, 0, 9, 1));

    public JumpCooldownModule() {
        super("JumpCooldown", Category.PLAYER);
    }

    @Override
    public void onTick() {
        bridge().writeField(bridge().player(), ClassType.LIVING_ENTITY, "jumpTicks", cooldown.asInt());
    }
}
