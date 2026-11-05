package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 持剑攻击后执行一次真实使用动作，形成 blockhit。 */
public final class BlockHitModule extends GameModule {
    private final NumberValue angle = add(new NumberValue("Angle", 90, 0, 360, 1));
    private final NumberValue distance = add(new NumberValue("Distance", 5, 0, 6, 0.1));
    private final ModeValue mode = add(new ModeValue("Mode", "Manual", "Manual", "Predict", "Auto", "Lag"));
    private boolean rightHeld;
    private long blockAt;

    public BlockHitModule() {
        super("BlockHit", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, this::onAttack);
        listen(MouseButtonEvent.class, event -> {
            if (event.button == 1) {
                rightHeld = event.pressed;
            }
        });
    }

    @Override
    public void onTick() {
        ActionApi actions = actions();
        if (blockAt != 0L && System.currentTimeMillis() >= blockAt && actions != null) {
            actions.useItem(false);
            blockAt = 0L;
        }
    }

    private void onAttack(AttackEvent event) {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (!event.post || bridge == null || actions == null
                || bridge.distance(bridge.player(), event.target) > distance.get()
                || !bridge.itemMatches(bridge.heldItem(bridge.player(), false), "swords")
                || mode.is("Manual") && !rightHeld || !insideAngle(bridge, event.target)) {
            return;
        }
        if (mode.is("Lag")) {
            blockAt = System.currentTimeMillis() + 75L;
        } else {
            actions.useItem(false);
        }
    }

    private boolean insideAngle(GameBridge bridge, Object target) {
        double[] from = bridge.entityPosition(bridge.player());
        double[] to = bridge.entityPosition(target);
        if (from == null || to == null) {
            return false;
        }
        float yaw = dev.nocturne.client.control.RotationController.yawTo(to[0] - from[0], to[2] - from[2]);
        float difference = Math.abs(dev.nocturne.client.control.RotationController.wrap(yaw - state().yaw()));
        return difference <= angle.get() * 0.5;
    }
}
