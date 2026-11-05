package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.Random;

/** 按目标受伤窗口选择攻击，或取消不合适窗口中的右键攻击。 */
public final class HitSelectModule extends GameModule {
    private final ModeValue mode = add(new ModeValue(
            "Mode", "Right-click use cancel", "Right-click use cancel", "Normal"));
    private final NumberValue chance = add(new NumberValue("Chance", 90, 0, 100, 1));
    private final Random random = new Random();

    public HitSelectModule() {
        super("HitSelect", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, this::onAttack);
    }

    private void onAttack(AttackEvent event) {
        if (event.post || random.nextDouble() * 100.0 >= chance.get()) {
            return;
        }
        int hurtTime = bridge().hurtTime(event.target);
        boolean unsuitable = mode.is("Normal") ? hurtTime > 0
                : state().onGround() && hurtTime > 12;
        if (unsuitable) {
            event.cancelled = true;
        }
    }
}
