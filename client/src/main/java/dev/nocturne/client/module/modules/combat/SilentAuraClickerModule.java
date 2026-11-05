package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** SilentAura 的攻击节拍器；仅在父模块启用时工作。 */
public final class SilentAuraClickerModule extends GameModule {
    private long nextAttack;

    public SilentAuraClickerModule() {
        super("auraClicker", Category.COMBAT);
    }

    @Override
    public void onTick() {
        SilentAuraModule aura = (SilentAuraModule) client().modules().get("SilentAura");
        if (aura == null || !aura.isEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (nextAttack != 0L && now < nextAttack) {
            return;
        }
        aura.attackTick();
        nextAttack = now + aura.attackDelayNanos();
    }

    @Override
    protected void onStop() {
        nextAttack = 0L;
    }
}
