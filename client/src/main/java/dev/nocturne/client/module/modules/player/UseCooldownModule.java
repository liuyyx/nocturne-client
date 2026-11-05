package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/** UseCooldown：把右键使用冷却限制到配置 tick。 */
public final class UseCooldownModule extends GameModule {
    private final NumberValue cooldown = add(new NumberValue("Cooldown", 0, 0, 4, 1));

    public UseCooldownModule() {
        super("Use Cooldown", Category.PLAYER);
    }

    @Override
    public void onTick() {
        Object current = bridge().readField(bridge().minecraft(), ClassType.MINECRAFT, "rightClickDelay");
        if (current instanceof Number && ((Number) current).intValue() > cooldown.asInt()) {
            bridge().writeField(bridge().minecraft(), ClassType.MINECRAFT, "rightClickDelay", cooldown.asInt());
        }
    }
}
