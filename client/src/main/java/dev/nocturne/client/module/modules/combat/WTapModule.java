package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.Random;

/** 攻击时短暂停止再恢复疾跑，形成真实 WTap 重置。 */
public final class WTapModule extends GameModule {
    private final NumberValue chance = add(new NumberValue("Chance", 90, 0, 100, 1));
    private final NumberValue releaseDelay = add(new NumberValue("Release delay", 0, 0, 500, 50));
    private final NumberValue rePressDelay = add(new NumberValue("Re-press delay", 0, 0, 500, 50));
    private final BooleanValue selectHits = add(new BooleanValue("Select hits", true));
    private final Random random = new Random();
    private long releaseAt;
    private long rePressAt;
    private boolean sprintingBefore;

    public WTapModule() {
        super("WTap", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, this::onAttack);
    }

    private void onAttack(AttackEvent event) {
        if (event.post || rePressAt != 0L || random.nextDouble() * 100.0 >= chance.get()
                || selectHits.get() && bridge().hurtTime(event.target) > 0) {
            return;
        }
        long now = System.currentTimeMillis();
        sprintingBefore = state().sprinting();
        releaseAt = now + releaseDelay.asInt();
        rePressAt = releaseAt + rePressDelay.asInt();
    }

    @Override
    public void onTick() {
        ActionApi actions = actions();
        if (actions == null || rePressAt == 0L) {
            return;
        }
        long now = System.currentTimeMillis();
        if (releaseAt != 0L && now >= releaseAt) {
            actions.setSprinting(false);
            releaseAt = 0L;
        }
        if (now >= rePressAt) {
            actions.setSprinting(sprintingBefore);
            rePressAt = 0L;
        }
    }

    @Override
    protected void onStop() {
        if (rePressAt != 0L && actions() != null) {
            actions().setSprinting(sprintingBefore);
        }
        releaseAt = 0L;
        rePressAt = 0L;
    }
}
