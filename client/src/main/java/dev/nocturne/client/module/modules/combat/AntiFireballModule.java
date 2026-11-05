package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** 追踪近距离箭实体，瞄准并在可达时攻击，对应 OpenVape 的 AntiFireball。 */
public final class AntiFireballModule extends GameModule {
    private final NumberValue angleLimit = add(new NumberValue("Angle limit", 45, 1, 180, 1));
    private final NumberValue aimSpeed = add(new NumberValue("Aim speed", 9, 1, 10, 0.1));
    private final BooleanValue stopMovement = add(new BooleanValue("Stop movement", false));
    private final BooleanValue silentAim = add(new BooleanValue("Silent aim", false));
    private Object arrow;

    public AntiFireballModule() {
        super("AntiFireball", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(MoveEvent.class, event -> {
            if (arrow != null && stopMovement.get()) {
                event.x = 0.0;
                event.z = 0.0;
            }
        });
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || !state.present()) {
            arrow = null;
            return;
        }
        arrow = nearestArrow(bridge, state);
        if (arrow == null) {
            return;
        }
        double[] position = bridge.entityPosition(arrow);
        if (position == null) {
            arrow = null;
            return;
        }
        double dx = position[0] - state.x();
        double dz = position[2] - state.z();
        float yaw = RotationController.approach(state.yaw(), RotationController.yawTo(dx, dz), aimSpeed.asFloat());
        float pitch = RotationController.pitchTo(Math.sqrt(dx * dx + dz * dz), position[1] - state.y() - 1.62);
        ActionApi actions = actions();
        if (actions != null) {
            if (silentAim.get()) {
                actions.sendRotation(yaw, pitch, state.onGround());
            } else {
                client().rotations().request(yaw, pitch, 7, 150_000_000L);
            }
            if (bridge.distance(state.player(), arrow) <= 3.0) {
                actions.attack(arrow);
            }
        }
    }

    private Object nearestArrow(GameBridge bridge, PlayerState state) {
        Object entities = bridge.entities();
        Object best = null;
        double bestDistance = Double.MAX_VALUE;
        if (entities instanceof Iterable<?>) {
            for (Object candidate : (Iterable<?>) entities) {
                double distance = consider(bridge, state, candidate);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private double consider(GameBridge bridge, PlayerState state, Object candidate) {
        if (!bridge.isInstance(candidate, ClassType.ABSTRACT_ARROW)) {
            return Double.MAX_VALUE;
        }
        double distance = bridge.distance(state.player(), candidate);
        if (distance > 6.0) {
            return Double.MAX_VALUE;
        }
        double[] from = bridge.entityPosition(state.player());
        double[] to = bridge.entityPosition(candidate);
        float yaw = RotationController.yawTo(to[0] - from[0], to[2] - from[2]);
        return Math.abs(RotationController.wrap(yaw - state.yaw())) > angleLimit.get()
                ? Double.MAX_VALUE : distance;
    }

    @Override
    protected void onStop() {
        arrow = null;
    }
}
