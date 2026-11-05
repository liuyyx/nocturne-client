package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;

/** 准星指向实体且冷却就绪时触发真实攻击。 */
public final class TriggerbotModule extends GameModule {
    private final BooleanValue waitForCooldown = add(new BooleanValue("Wait for cooldown", true));

    public TriggerbotModule() {
        super("Triggerbot", Category.COMBAT);
    }

    @Override
    public void onTick() {
        ActionApi actions = actions();
        GameBridge bridge = bridge();
        if (actions == null || bridge == null
                || waitForCooldown.get() && state().attackStrengthScale() < 1.0f) {
            return;
        }
        Object hit = actions.pick(3.0, 1.0f);
        if (bridge.isEntityHit(hit)) {
            actions.attack(bridge.callMapped(hit, ClassType.ENTITY_HIT_RESULT, "getEntity"));
        }
    }
}
