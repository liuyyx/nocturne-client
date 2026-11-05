package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** 坠落达到条件时在脚下放置梯子，并在完成后恢复原槽。 */
public final class AutoLadderModule extends GameModule {
    private final BooleanValue onLethalFall = add(new BooleanValue("On lethal fall", true));
    private final BooleanValue onMoreThan = add(new BooleanValue("On more than x blocks", false));
    private final NumberValue blocks = add(new NumberValue("Blocks", 6, 3, 10, 1));
    private final BooleanValue silentAim = add(new BooleanValue("Silent aim", false));
    private final BooleanValue returnSlot = add(new BooleanValue("Return to last slot", true));

    public AutoLadderModule() {
        super("AutoLadder", Category.WORLD);
    }

    @Override
    public void onTick() {
        PlayerState state = state();
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (!state.present() || bridge == null || actions == null || state.onGround()) {
            return;
        }
        float fall = bridge.fallDistance(state.player());
        if (!(onMoreThan.get() && fall >= blocks.get()
                || onLethalFall.get() && fall >= state.health() + 2.0f)) {
            return;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (!bridge.itemDescription(bridge.hotbarStack(slot)).contains("ladder")) {
                continue;
            }
            actions.selectSlot(slot, silentAim.get());
            if (silentAim.get()) {
                actions.sendRotation(state.yaw(), 90.0f, false);
            } else {
                actions.setRotation(state.yaw(), 90.0f);
            }
            if (PlacementSupport.placeNear(bridge, actions,
                    (int) Math.floor(state.x()), (int) Math.floor(state.y()) - 1,
                    (int) Math.floor(state.z())) && returnSlot.get()) {
                actions.selectSlot(state.selectedSlot(), silentAim.get());
            }
            return;
        }
    }
}
