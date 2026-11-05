package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.Friends;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.render.WorldGeometry;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon AimBot：AimAssist 与 BowAim 共用目标过滤，分别执行视角辅助和弹道瞄准。 */
public final class AimBotModule extends GameModule implements WorldOverlay {
    private final ModeValue mode = add(new ModeValue("Mode", "AimAssist", "AimAssist", "BowAim"));
    private final ModeValue rotation = add(new ModeValue("Rotation", "Silent", "Client", "Silent"));
    private final NumberValue aimStrength = add(new NumberValue("Aim Strength", 30, 1, 100, 1));
    private final NumberValue aimSmooth = add(new NumberValue("Aim Smooth", 45, 1, 180, 1));
    private final NumberValue aimTime = add(new NumberValue("Aim Time", 2, 1, 10, 1));
    private final BooleanValue onlyWeapon = add(new BooleanValue("Only Weapon", false));
    private final BooleanValue lmbActivation = add(new BooleanValue("LMB Activation", false));
    private final BooleanValue ignoreWalls = add(new BooleanValue("Ignore Walls", true));
    private final NumberValue reactionTime = add(new NumberValue("Reaction Time", 80, 1, 500, 1));
    private final BooleanValue ignoreInvis = add(new BooleanValue("Ignore Invis", false));
    private final NumberValue predictTicks = add(new NumberValue("Predict Ticks", 2, 0, 20, 1));
    private TargetManager.Target target;
    private int lockTicks;
    private long visibleSince;
    private boolean attackHeld;

    public AimBotModule() {
        super("AimBot", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(MouseButtonEvent.class, event -> {
            if (event.button == 0) {
                attackHeld = event.pressed;
            }
        });
    }

    @Override
    public void onTick() {
        if (lmbActivation.get() && !attackHeld || onlyWeapon.get() && !weapon()) {
            target = null;
            return;
        }
        TargetManager.Options options = EpsilonSupport.targets(8.0, true, false, !ignoreInvis.get(),
                TargetManager.Sort.ANGLE);
        TargetManager.Target found = client().targets().find(client(), options);
        if (found == null || Friends.isFriend(bridge(), found.entity())) {
            target = null;
            visibleSince = 0L;
            return;
        }
        if (target == null || target.entity() != found.entity() || lockTicks-- <= 0) {
            target = found;
            lockTicks = aimTime.asInt();
            visibleSince = 0L;
        }
        if (!ignoreWalls.get() && !bridge().hasLineOfSight(bridge().player(), target.entity())) {
            visibleSince = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (visibleSince == 0L) {
            visibleSince = now;
        }
        if (!ignoreWalls.get() && now - visibleSince < reactionTime.asInt()) {
            return;
        }
        double x = target.x();
        double y = target.aimY();
        double z = target.z();
        float pitch;
        if (mode.is("BowAim")) {
            double[] velocity = bridge().entityVelocity(target.entity());
            int ticks = predictTicks.asInt();
            x += velocity[0] * ticks;
            y += velocity[1] * ticks;
            z += velocity[2] * ticks;
            pitch = AutoThrowModule.solvePitch(Math.hypot(x - state().x(), z - state().z()),
                    y - state().y() - 1.52);
        } else {
            pitch = RotationController.pitchTo(Math.hypot(x - state().x(), z - state().z()),
                    y - state().y() - 1.62);
        }
        float yaw = RotationController.yawTo(x - state().x(), z - state().z());
        float speed = aimSmooth.asFloat() * aimStrength.asFloat() / 100.0f;
        float nextYaw = RotationController.approach(state().yaw(), yaw, speed);
        float nextPitch = RotationController.approach(state().pitch(), pitch, speed);
        if (rotation.is("Client") && actions() != null) {
            actions().setRotation(nextYaw, nextPitch);
        } else if (client() != null) {
            client().rotations().request(nextYaw, nextPitch, 4, 150_000_000L);
        }
    }

    private boolean weapon() {
        return bridge().itemMatches(bridge().hotbarStack(state().selectedSlot()),
                "sword,bow,crossbow,trident,spear,mace");
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (target != null) {
            WorldGeometry.entity(bridge(), draw, projection, target.entity(), 1.0f, 0xFFFF92B0);
        }
    }

    @Override
    protected void onStop() {
        target = null;
        attackHeld = false;
        visibleSince = 0L;
    }
}
