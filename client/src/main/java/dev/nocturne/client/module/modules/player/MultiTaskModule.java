package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** MultiTask：使用物品时仍允许真实左键攻击准星实体。 */
public final class MultiTaskModule extends GameModule {
    private boolean attacking;

    public MultiTaskModule() {
        super("MultiTask", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(MouseButtonEvent.class, event -> {
            if (event.button == 0) {
                attacking = event.pressed;
            }
        });
    }

    @Override
    public void onTick() {
        if (!attacking || !bridge().usingItem(bridge().player()) || state().attackStrengthScale() < 1.0f) {
            return;
        }
        Object target = bridge().hitEntity(actions().pick(4.5, 1.0f));
        if (target != null) {
            actions().attack(target);
        }
    }

    @Override
    protected void onStop() {
        attacking = false;
    }
}
