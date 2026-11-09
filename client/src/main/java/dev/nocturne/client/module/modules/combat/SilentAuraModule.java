package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** 只向服务器提交瞄准朝向，再由配套 clicker 执行攻击。 */
public final class SilentAuraModule extends GameModule {
    /** 攻击前允许的 yaw 误差。 */
    private static final float ATTACK_ALIGNMENT_DEGREES = 4.0f;
    private final NumberValue aimSpeed = add(new NumberValue("Aim speed", 7, 1, 10, 0.1));
    private final NumberValue attacksPerSecond = add(new NumberValue("Attacks per Second", 6, 1, 20, 0.5));
    private final NumberValue extraDistance = add(new NumberValue("Extra swing distance", 1, 0, 3, 0.1));
    private final NumberValue maxAngle = add(new NumberValue("Max angle", 120, 1, 360, 1));
    private final BooleanValue limitItems = add(new BooleanValue("Limit to items", false));
    private final dev.nocturne.client.value.TextValue allowedItems =
            add(new dev.nocturne.client.value.TextValue("Allowed Items", "swords", 256));
    private final TargetManager.Options options = new TargetManager.Options();
    private TargetManager.Target target;
    private float yaw;
    private float pitch;
    private boolean aimed;

    public SilentAuraModule() {
        super("SilentAura", Category.COMBAT);
        options.mobs = true;
    }

    @Override
    protected void onStart() {
        GameModule clicker = (GameModule) client().modules().get("auraClicker");
        if (clicker != null) {
            clicker.setEnabled(true);
        }
    }

    @Override
    protected void onStop() {
        GameModule clicker = (GameModule) client().modules().get("auraClicker");
        if (clicker != null) {
            clicker.setEnabled(false);
        }
        target = null;
        aimed = false;
    }

    @Override
    public void onTick() {
        PlayerState state = state();
        if (!state.present()) {
            target = null;
            return;
        }
        options.range = 3.0 + extraDistance.get();
        options.maxAngle = maxAngle.get();
        options.sort = TargetManager.Sort.DISTANCE;
        target = client().targets().find(client(), options);
        if (target == null) {
            aimed = false;
            return;
        }
        if (!aimed) {
            yaw = state.yaw();
            pitch = state.pitch();
            aimed = true;
        }
        double dx = target.x() - state.x();
        double dz = target.z() - state.z();
        yaw = RotationController.approach(yaw, RotationController.yawTo(dx, dz), aimSpeed.asFloat());
        pitch = RotationController.approach(pitch, RotationController.pitchTo(
                Math.sqrt(dx * dx + dz * dz), target.aimY() - state.y() - 1.62), aimSpeed.asFloat());
        ActionApi actions = actions();
        if (actions != null) {
            actions.sendRotation(yaw, pitch, state.onGround());
        }
    }

    /**
     * 尝试一次攻击。
     *
     * @return 是否真的打出去了；未对准/未收敛/被 HitSelect 取消时返回 false，
     *         调用方据此决定是否推进节拍（不能把 canceled 当成 successful）
     */
    boolean attackTick() {
        if (target == null || !isEnabled()) {
            return false;
        }
        ActionApi actions = actions();
        if (actions == null || !aimed || limitItems.get()
                && !bridge().itemMatches(bridge().heldItem(state().player(), false), allowedItems.get())) {
            return false;
        }
        // 攻击门：本模块自己平滑 yaw，未收敛到目标角（或目标已离开）时不发攻击。
        double dx = target.x() - state().x();
        double dz = target.z() - state().z();
        float targetYaw = RotationController.yawTo(dx, dz);
        if (Math.abs(RotationController.wrap(targetYaw - yaw)) > ATTACK_ALIGNMENT_DEGREES) {
            return false;
        }
        return actions.attack(target.entity());
    }

    long attackDelayNanos() {
        return (long) (1_000_000_000.0 / attacksPerSecond.get());
    }
}
