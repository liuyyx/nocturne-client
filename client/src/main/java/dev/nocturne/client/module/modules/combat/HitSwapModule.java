package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;

/** 按盾牌/下落状态在攻击前切换斧、锤或剑。 */
public final class HitSwapModule extends GameModule {
    private final BooleanValue maces = add(new BooleanValue("Maces", true));
    private final BooleanValue axes = add(new BooleanValue("Axes", true));
    private final BooleanValue swords = add(new BooleanValue("Swords", false));
    private int restore = -1;

    public HitSwapModule() {
        super("HitSwap", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, this::onAttack);
    }

    private void onAttack(AttackEvent event) {
        if (event.post) {
            return;
        }
        GameBridge bridge = bridge();
        String rule = maces.get() && bridge.fallDistance(bridge.player()) > 1.5f ? "mace"
                : axes.get() && bridge.usingItem(event.target) ? "axe" : swords.get() ? "sword" : "";
        if (rule.isEmpty()) {
            return;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (bridge.itemMatches(bridge.hotbarStack(slot), rule)) {
                restore = state().selectedSlot();
                actions().selectSlot(slot, true);
                return;
            }
        }
    }

    @Override
    public void onTick() {
        if (restore >= 0) {
            ActionApi actions = actions();
            if (actions != null) {
                actions.selectSlot(restore, true);
            }
            restore = -1;
        }
    }

    @Override
    protected void onStop() {
        restore = -1;
    }
}
