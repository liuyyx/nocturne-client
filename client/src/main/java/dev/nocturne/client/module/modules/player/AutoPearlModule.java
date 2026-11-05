package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

/** 瞄准附近的敌方珍珠轨迹并跟投自己的珍珠。 */
public final class AutoPearlModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "On bind", "On bind", "Aggro"));
    private final NumberValue aimSpeed = add(new NumberValue("Aim speed", 5, 0.1, 15, 0.5));
    private final NumberValue angleLimit = add(new NumberValue("Angle limit", 180, 30, 360, 5));
    private final NumberValue minHealth = add(new NumberValue("Min health", 5, 1, 20, 1));
    private final NumberValue distanceLimit = add(new NumberValue("Distance limit", 6, 0, 10, 0.1));
    private final BooleanValue verticalCheck = add(new BooleanValue("Vertical check", false));
    private final NumberValue cooldown = add(new NumberValue("Pearl cooldown", 1, 0, 15, 0.1));
    private final BooleanValue silentAim = add(new BooleanValue("Silent aim", false));
    private final BooleanValue limitItems = add(new BooleanValue("Limit to items", true));
    private final TextValue allowedItems = add(new TextValue("Allowed items", "swords,ender_pearl", 256));
    private long nextThrow;

    public AutoPearlModule() {
        super("AutoPearl", Category.PLAYER);
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        PlayerState state = state();
        if (bridge == null || actions == null || !state.present() || state.health() < minHealth.get()
                || System.currentTimeMillis() < nextThrow) {
            return;
        }
        Object pearl = nearestPearl(bridge, state);
        if (pearl == null) {
            if (mode.is("On bind")) {
                setEnabled(false);
            }
            return;
        }
        if (limitItems.get() && !bridge.itemMatches(bridge.heldItem(state.player(), false), allowedItems.get())) {
            return;
        }
        double[] pos = bridge.entityPosition(pearl);
        double dx = pos[0] - state.x();
        double dz = pos[2] - state.z();
        float yaw = RotationController.approach(state.yaw(), RotationController.yawTo(dx, dz), aimSpeed.asFloat());
        float pitch = RotationController.approach(state.pitch(), RotationController.pitchTo(
                Math.sqrt(dx * dx + dz * dz), pos[1] - state.y() - 1.62), aimSpeed.asFloat());
        if (silentAim.get()) {
            actions.sendRotation(yaw, pitch, state.onGround());
        } else {
            actions.setRotation(yaw, pitch);
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (bridge.itemDescription(bridge.hotbarStack(slot)).contains("ender_pearl")) {
                actions.selectSlot(slot, silentAim.get());
                actions.useItem(false);
                nextThrow = System.currentTimeMillis() + (long) (cooldown.get() * 1000.0);
                if (mode.is("On bind")) {
                    setEnabled(false);
                }
                return;
            }
        }
    }

    private Object nearestPearl(GameBridge bridge, PlayerState state) {
        Object entities = bridge.entities();
        Object best = null;
        double bestDistance = Double.MAX_VALUE;
        if (entities instanceof Iterable<?>) {
            for (Object entity : (Iterable<?>) entities) {
                if (!bridge.isInstance(entity, ClassType.THROWN_ENDER_PEARL)) {
                    continue;
                }
                double distance = bridge.distance(state.player(), entity);
                double[] pos = bridge.entityPosition(entity);
                if (distance < distanceLimit.get() || verticalCheck.get() && Math.abs(pos[1] - state.y()) > 7.0) {
                    continue;
                }
                double[] from = bridge.entityPosition(state.player());
                float yaw = RotationController.yawTo(pos[0] - from[0], pos[2] - from[2]);
                if (Math.abs(RotationController.wrap(yaw - state.yaw())) > angleLimit.get() * 0.5) {
                    continue;
                }
                if (distance < bestDistance) {
                    best = entity;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }
}
