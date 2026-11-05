package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** 通过鱼钩状态、受伤实体和向下速度识别咬钩并收竿。 */
public final class AutoFishModule extends GameModule {
    private final BooleanValue recastCaught = add(new BooleanValue("Recast caught", false));
    private final BooleanValue recastGround = add(new BooleanValue("Recast ground", false));
    private final BooleanValue refillRods = add(new BooleanValue("Refill rods", true));
    private final NumberValue clickDelay = add(new NumberValue("Click delay", 75, 50, 200, 5));
    private long nextUse;
    private long groundedAt;

    public AutoFishModule() {
        super("AutoFish", Category.WORLD);
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (bridge == null || actions == null) {
            return;
        }
        if (!equipRod(bridge, actions)) {
            if (!refillRods.get()) {
                setEnabled(false);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextUse) {
            return;
        }
        Object hook = bridge.fishingHook();
        if (hook == null) {
            actions.useItem(false);
            nextUse = now + 500L;
            return;
        }
        if (bridge.fishingCaughtEntity() != null && !recastCaught.get()) {
            setEnabled(false);
            return;
        }
        Number motionY = number(bridge.readField(hook, ClassType.ENTITY, "motionY"));
        boolean ground = Boolean.TRUE.equals(bridge.readField(hook, ClassType.ENTITY, "onGround"));
        groundedAt = ground ? groundedAt == 0L ? now : groundedAt : 0L;
        if (motionY != null && motionY.doubleValue() <= -0.05
                || recastGround.get() && groundedAt != 0L && now - groundedAt >= 3000L) {
            actions.useItem(false);
            nextUse = now + clickDelay.asInt() + 300L;
            groundedAt = 0L;
        }
    }

    private boolean equipRod(GameBridge bridge, ActionApi actions) {
        if (bridge.itemDescription(bridge.heldItem(bridge.player(), false)).contains("fishing_rod")) {
            return true;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (bridge.itemDescription(bridge.hotbarStack(slot)).contains("fishing_rod")) {
                return actions.selectSlot(slot, false);
            }
        }
        if (refillRods.get() && !bridge.containerOpen()) {
            for (int slot = 9; slot <= 35; slot++) {
                if (bridge.itemDescription(bridge.inventoryStack(slot)).contains("fishing_rod")) {
                    return actions.clickSlot(slot, state().selectedSlot(), "swap");
                }
            }
        }
        return false;
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }
}
