package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/**  lethal 坠落时使用水桶或蜘蛛网，并可延迟收回水。 */
public final class MlgModule extends GameModule {
    private final BooleanValue useBuckets = add(new BooleanValue("Use buckets", true));
    private final BooleanValue pickUpWater = add(new BooleanValue("Pick up water", false));
    private final BooleanValue useCobwebs = add(new BooleanValue("Use cobwebs", true));
    private final BooleanValue onLethalFall = add(new BooleanValue("On lethal fall", true));
    private final BooleanValue onDamage = add(new BooleanValue("On at least X damage", false));
    private final NumberValue health = add(new NumberValue("Health", 5, 1, 20, 1));
    private final NumberValue interactDelay = add(new NumberValue("Interact Delay", 60, 0, 300, 5));
    private final NumberValue collectDelayTicks = add(new NumberValue("Collect Delay Ticks", 2, 0, 10, 1));
    private long pickupAt;
    private long nextInteract;

    public MlgModule() {
        super("Auto MLG", Category.PLAYER);
    }

    @Override
    public void onTick() {
        PlayerState state = state();
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (!state.present() || bridge == null || actions == null) {
            return;
        }
        if (pickupAt != 0L) {
            if (System.currentTimeMillis() >= pickupAt) {
                actions.useItem(false);
                pickupAt = 0L;
            }
            return;
        }
        float fall = bridge.fallDistance(state.player());
        boolean activate = !state.onGround() && (onLethalFall.get() && fall >= state.health() + 2.0f
                || onDamage.get() && fall >= health.get() + 2.0f);
        if (!activate || System.currentTimeMillis() < nextInteract) {
            return;
        }
        nextInteract = System.currentTimeMillis() + interactDelay.asInt();
        for (int slot = 0; slot <= 8; slot++) {
            String id = bridge.itemDescription(bridge.hotbarStack(slot));
            if (!(useBuckets.get() && id.contains("water_bucket")
                    || useCobwebs.get() && id.contains("cobweb"))) {
                continue;
            }
            actions.selectSlot(slot, false);
            actions.sendRotation(state.yaw(), 90.0f, false);
            if (PlacementSupport.placeNear(bridge, actions, (int) Math.floor(state.x()),
                    (int) Math.floor(state.y()) - 1, (int) Math.floor(state.z()))
                    && pickUpWater.get() && id.contains("water_bucket")) {
                pickupAt = System.currentTimeMillis() + collectDelayTicks.asInt() * 50L;
            }
            return;
        }
    }

    @Override
    protected void onStop() {
        pickupAt = 0L;
    }
}
