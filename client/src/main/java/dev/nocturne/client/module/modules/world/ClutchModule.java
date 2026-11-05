package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** 坠落时在预测脚下区域放置方块，形成基础 clutch。 */
public final class ClutchModule extends GameModule {
    private final BooleanValue onVoid = add(new BooleanValue("On void", true));
    private final BooleanValue onLethalFall = add(new BooleanValue("On lethal fall", true));
    private final BooleanValue onMoreThan = add(new BooleanValue("On more than x blocks", false));
    private final NumberValue blocks = add(new NumberValue("Blocks", 6, 3, 10, 1));
    private final BooleanValue limitBlocks = add(new BooleanValue("Limit blocks", false));
    private final NumberValue maxBlocks = add(new NumberValue("Max blocks", 5, 1, 10, 1));
    private int placed;

    public ClutchModule() {
        super("Clutch", Category.WORLD);
    }

    @Override
    protected void onStart() {
        placed = 0;
    }

    @Override
    public void onTick() {
        PlayerState state = state();
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (!state.present() || bridge == null || actions == null) {
            return;
        }
        if (state.onGround()) {
            placed = 0;
            return;
        }
        float fall = bridge.fallDistance(state.player());
        boolean activate = onMoreThan.get() && fall >= blocks.get()
                || onLethalFall.get() && fall >= state.health() + 2.0f
                || onVoid.get() && fall >= 6.0f;
        if (!activate || limitBlocks.get() && placed >= maxBlocks.asInt()) {
            return;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (!bridge.itemMatches(bridge.hotbarStack(slot), "blocks")) {
                continue;
            }
            actions.selectSlot(slot, false);
            if (PlacementSupport.placeNear(bridge, actions, (int) Math.floor(state.x()),
                    (int) Math.floor(state.y()) - 1, (int) Math.floor(state.z()))) {
                placed++;
            }
            return;
        }
    }
}
