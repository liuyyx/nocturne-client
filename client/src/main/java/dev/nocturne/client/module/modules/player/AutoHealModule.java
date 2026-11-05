package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 血量低于阈值时使用热栏治疗物，并在结束后恢复槽位。 */
public final class AutoHealModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Legit", "Legit", "Silent"));
    private final BooleanValue soup = add(new BooleanValue("Soup", false));
    private final BooleanValue potions = add(new BooleanValue("Potions", false));
    private final NumberValue health = add(new NumberValue("Health", 17, 1, 20, 1));
    private final NumberValue delay = add(new NumberValue("Delay", 500, 50, 1000, 50));
    private long nextHeal;

    public AutoHealModule() {
        super("AutoHeal", Category.PLAYER);
    }

    @Override
    public void onTick() {
        PlayerState state = state();
        ActionApi actions = actions();
        GameBridge bridge = bridge();
        if (!state.present() || actions == null || bridge == null || state.health() > health.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextHeal) {
            return;
        }
        for (int slot = 0; slot <= 8; slot++) {
            String id = bridge.itemDescription(bridge.hotbarStack(slot));
            boolean wanted = soup.get() && (id.contains("soup") || id.contains("stew"))
                    || potions.get() && id.contains("splash_potion");
            if (!wanted) {
                continue;
            }
            actions.selectSlot(slot, mode.is("Silent"));
            actions.useItem(false);
            if (mode.is("Silent")) {
                actions.selectSlot(state.selectedSlot(), true);
            }
            nextHeal = now + delay.asInt();
            return;
        }
    }
}
