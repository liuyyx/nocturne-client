package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** 下落重击前切到重锤；可启用自动瞄准和自动攻击。 */
public final class AutoMaceModule extends GameModule {
    private final BooleanValue smashOnly = add(new BooleanValue("Smash only", true));
    private final BooleanValue aim = add(new BooleanValue("Aim", false));
    private final BooleanValue attack = add(new BooleanValue("Attack", false));
    private final NumberValue aimRange = add(new NumberValue("Aim range", 6, 2, 10, 0.5));
    private final TargetManager.Options options = new TargetManager.Options();

    public AutoMaceModule() {
        super("AutoMace", Category.COMBAT);
        options.mobs = true;
        options.invisibles = true;
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, event -> {
            if (!event.post) {
                equipMace();
            }
        });
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null || smashOnly.get() && bridge.fallDistance(bridge.player()) < 1.5f) {
            return;
        }
        TargetManager.Target target = client().targets().find(client(), configuredOptions());
        if (target == null) {
            return;
        }
        boolean aligned = true;
        if (aim.get()) {
            double[] from = bridge.entityPosition(bridge.player());
            if (from == null) {
                return;
            }
            double dx = target.x() - from[0];
            double dz = target.z() - from[2];
            float yaw = RotationController.yawTo(dx, dz);
            boolean accepted = client().rotations().request(yaw, RotationController.pitchTo(
                    Math.sqrt(dx * dx + dz * dz), target.aimY() - from[1] - 1.62), 5, 150_000_000L);
            // 请求被高优先级抢占、或尚未收敛到目标角时不能攻击：那一击用的仍是旧朝向。
            aligned = accepted && client().rotations().isAimedAt(yaw, 4.0f);
        }
        if (attack.get() && aligned && state().attackStrengthScale() >= 1.0f && equipMace()) {
            actions().attack(target.entity());
        }
    }

    private TargetManager.Options configuredOptions() {
        options.range = aimRange.get();
        options.maxAngle = 360.0;
        return options;
    }

    private boolean equipMace() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        if (bridge == null || actions == null) {
            return false;
        }
        if (bridge.itemMatches(bridge.heldItem(bridge.player(), false), "mace")) {
            return true;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (bridge.itemMatches(bridge.hotbarStack(slot), "mace")) {
                return actions.selectSlot(slot, true);
            }
        }
        return false;
    }
}
