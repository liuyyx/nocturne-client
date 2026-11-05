package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.NumberValue;

/** AutoThrow：以雪球/鸡蛋的重力和阻尼迭代求解抛物线，旋转后投掷。 */
public final class AutoThrowModule extends GameModule {
    private final NumberValue minRange = add(new NumberValue("Min Range", 3, 0, 10, 0.1));
    private final NumberValue maxRange = add(new NumberValue("Max Range", 8, 2, 16, 0.1));
    private final NumberValue fov = add(new NumberValue("Fov", 90, 1, 360, 1));
    private final NumberValue delay = add(new NumberValue("Delay", 500, 0, 2000, 50));
    private final NumberValue rotationSpeed = add(new NumberValue("Rotation Speed", 180, 1, 180, 1));
    private final NumberValue rotationPriority = add(new NumberValue("Rotation Priority", 1, 1, 9, 1));
    private long nextThrow;

    public AutoThrowModule() {
        super("Auto Throw", Category.COMBAT);
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now < nextThrow || EpsilonSupport.enabled("Scaffold") || EpsilonSupport.enabled("KillAura")) {
            return;
        }
        TargetManager.Options options = EpsilonSupport.targets(maxRange.get(), true, true, false,
                TargetManager.Sort.DISTANCE);
        options.maxAngle = fov.get();
        TargetManager.Target target = client().targets().find(client(), options);
        if (target == null || target.distance() < minRange.get()) {
            return;
        }
        int slot = EpsilonSupport.findHotbar(bridge(), "snowball", "egg");
        if (slot < 0) {
            return;
        }
        double dx = target.x() - state().x();
        double dz = target.z() - state().z();
        double horizontal = Math.hypot(dx, dz);
        float yaw = RotationController.yawTo(dx, dz);
        float pitch = solvePitch(horizontal, target.aimY() - state().y() - 1.52);
        client().rotations().request(RotationController.approach(state().yaw(), yaw, rotationSpeed.asFloat()),
                RotationController.approach(state().pitch(), pitch, rotationSpeed.asFloat()),
                rotationPriority.asInt(), 150_000_000L);
        if (Math.abs(RotationController.wrap(yaw - client().rotations().yaw(state().yaw()))) < 3.0f
                && Math.abs(pitch - client().rotations().pitch(state().pitch())) < 3.0f) {
            int original = state().selectedSlot();
            actions().selectSlot(slot, false);
            if (actions().useItem()) {
                actions().swing(false, false);
                nextThrow = now + delay.asInt();
            }
            actions().selectSlot(original, false);
        }
    }

    static float solvePitch(double horizontal, double vertical) {
        float best = 0.0f;
        double bestError = Double.MAX_VALUE;
        for (int degree = -80; degree <= 60; degree += 2) {
            double pitch = Math.toRadians(degree);
            double vx = Math.cos(pitch) * 1.5;
            double vy = -Math.sin(pitch) * 1.5;
            double distance = 0.0;
            double y = 0.0;
            for (int tick = 0; tick < 80 && distance < horizontal; tick++) {
                distance += vx;
                y += vy;
                vx *= 0.99;
                vy = vy * 0.99 - 0.03;
            }
            double error = Math.abs(y - vertical);
            if (error < bestError) {
                bestError = error;
                best = degree;
            }
        }
        return best;
    }
}
