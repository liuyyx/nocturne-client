package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** ElytraCombat：目标预测、Follow/Mace/Spear 行为、A* 避让与鞘翅输入/速度控制。 */
public final class ElytraCombatModule extends GameModule implements WorldOverlay {
    private final ModeValue mode = add(new ModeValue("Mode", "Follow", "Follow", "Mace", "Spear"));
    private final KeyValue switchModeKey = add(new KeyValue("Switch Mode Key", KeyValue.NONE));
    private final NumberValue targetRange = add(new NumberValue("Target Range", 80, 8, 256, 1));
    private final BooleanValue dynamicTarget = add(new BooleanValue("Dynamic Target", false));
    private final BooleanValue onlyNoWasd = add(new BooleanValue("Only When No WASD", false));
    private final BooleanValue autoControlElytra = add(new BooleanValue("Auto Control Elytra", true));
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue mobs = add(new BooleanValue("Mobs", false));
    private final BooleanValue animals = add(new BooleanValue("Animals", false));
    private final BooleanValue villagers = add(new BooleanValue("Villagers", false));
    private final BooleanValue ambient = add(new BooleanValue("Ambient", false));
    private final BooleanValue water = add(new BooleanValue("Water", false));
    private final BooleanValue others = add(new BooleanValue("Others", false));
    private final BooleanValue invisible = add(new BooleanValue("Invisible", false));
    private final BooleanValue followFriend = add(new BooleanValue("Follow Friend", false));
    private final ModeValue predictor = add(new ModeValue("Predictor", "NV", "Linear", "Quadratic", "NV"));
    private final NumberValue predictTicks = add(new NumberValue("Predict Ticks", 2, 0, 20, 1));
    private final NumberValue predictionHistory = add(new NumberValue("Prediction History", 5, 2, 20, 1));
    private final NumberValue stopDistance = add(new NumberValue("Stop Distance", 6, 1, 32, 0.5));
    private final NumberValue followGroundHeight = add(new NumberValue("Follow Ground Height", 2, 0, 12, 0.5));
    private final BooleanValue pathfinding = add(new BooleanValue("Pathfinding", true));
    private final NumberValue searchRadius = add(new NumberValue("Search Radius", 24, 6, 64, 1));
    private final NumberValue maxNodes = add(new NumberValue("Max Nodes", 1200, 100, 6000, 100));
    private final NumberValue dataSize = add(new NumberValue("Data Size", 50, 25, 100, 5));
    private final NumberValue maceRange = add(new NumberValue("Mace Range", 10, 2, 16, 0.5));
    private final NumberValue maceHeight = add(new NumberValue("Mace Height", 10, 1, 32, 0.5));
    private final NumberValue maceGroundHeight = add(new NumberValue("Mace Ground Height", 10, 1, 32, 0.5));
    private final NumberValue maceMinFollowHeight = add(new NumberValue("Mace Min Follow Height", 1.5, 0, 12, 0.1));
    private final NumberValue maceYBias = add(new NumberValue("Mace Y Bias", 3, -10, 10, 0.1));
    private final NumberValue macePullUpTicks = add(new NumberValue("Mace Pull Up Max Ticks", 20, 1, 100, 1));
    private final BooleanValue maceUsePredictor = add(new BooleanValue("Mace Use Predictor", true));
    private final NumberValue maceAttackThreshold = add(new NumberValue("Mace Attack Threshold", 0.75, 0.1, 1, 0.05));
    private final BooleanValue maceSwingHand = add(new BooleanValue("Mace Swing Hand", true));
    private final BooleanValue maceAntiShield = add(new BooleanValue("Mace Anti Shield", true));
    private final BooleanValue maceSmoothFlight = add(new BooleanValue("Mace Smooth Flight", true));
    private final BooleanValue maceAngleOptimize = add(new BooleanValue("Mace Angle Optimize", false));
    private final NumberValue maceDownAngle = add(new NumberValue("Mace Down Angle", 30.5, 5, 80, 0.5));
    private final NumberValue spearRange = add(new NumberValue("Spear Range", 8, 2, 24, 0.5));
    private final NumberValue spearMinRange = add(new NumberValue("Spear Min Range", 1, 0, 8, 0.1));
    private final NumberValue spearLungeStrength = add(new NumberValue("Spear Lunge Strength", 3.7, 0.5, 15, 0.1));
    private final NumberValue spearPullOverTicks = add(new NumberValue("Spear Pull Over Ticks", 6, 1, 40, 1));
    private final BooleanValue spearUsePredictor = add(new BooleanValue("Spear Use Predictor", true));
    private final BooleanValue spearAntiSpear = add(new BooleanValue("Spear Anti Spear", true));
    private final NumberValue spearAntiExtraDistance = add(new NumberValue("Spear Anti Extra Distance", 1, 0, 8, 0.1));
    private final BooleanValue logSpearHit = add(new BooleanValue("Log Spear Hit", false));
    private final ModeValue controlMode = add(new ModeValue("Control Mode", "Input", "Input", "DirectVelocity"));
    private final NumberValue maxFlightSpeed = add(new NumberValue("Max Flight Speed", 2, 0.5, 10, 0.1));
    private final BooleanValue allowFirework = add(new BooleanValue("Allow Firework", true));
    private final BooleanValue renderVector = add(new BooleanValue("Render Vector", true));
    private final ColorValue renderColor = add(new ColorValue("Render Color", 0xD28585A1));
    private final NumberValue renderWidth = add(new NumberValue("Render Width", 2, 0.5, 8, 0.5));
    private final Map<Integer, Deque<double[]>> history = new HashMap<Integer, Deque<double[]>>();
    private TargetManager.Target target;
    private double[] waypoint;
    private int pathCooldown;
    private int behaviorTicks;
    private int fireworkCooldown;
    private boolean inputActive;

    public ElytraCombatModule() {
        super("Elytra Combat", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(KeyEvent.class, event -> {
            if (event.pressed && switchModeKey.bound() && event.keyCode == switchModeKey.get()) {
                mode.set(mode.is("Follow") ? "Mace" : mode.is("Mace") ? "Spear" : "Follow");
                resetBehavior();
            }
        });
        listen(MovementInputEvent.class, event -> {
            if (!inputActive || !controlMode.is("Input")) {
                return;
            }
            event.forward = 1.0f;
            event.sprint = true;
        });
    }

    @Override
    public void onTick() {
        inputActive = false;
        if (onlyNoWasd.get() && state().moving()) {
            resetBehavior();
            return;
        }
        if (target == null || dynamicTarget.get() || bridge().health(target.entity()) <= 0.0f) {
            TargetManager.Options options = EpsilonSupport.targets(targetRange.get(), players.get(),
                    mobs.get() || animals.get() || villagers.get() || ambient.get() || water.get() || others.get(),
                    invisible.get(), TargetManager.Sort.DISTANCE);
            java.util.List<TargetManager.Target> candidates = client().targets().list(client(), options);
            target = null;
            for (TargetManager.Target candidate : candidates) {
                if (followFriend.get() || !dev.nocturne.client.control.Friends.isFriend(bridge(), candidate.entity())) {
                    target = candidate;
                    break;
                }
            }
            resetBehavior();
        }
        if (target == null) {
            return;
        }
        recordHistory(target);
        double[] predicted = predict(target);
        double[] desired = desiredPosition(predicted);
        if (pathfinding.get() && --pathCooldown <= 0) {
            waypoint = ElytraPathfinder.next(bridge(), new double[]{state().x(), state().y(), state().z()},
                    desired, searchRadius.asInt(), Math.min(maxNodes.asInt(), dataSize.asInt() * 120));
            pathCooldown = 5;
        }
        double[] destination = waypoint == null ? desired : waypoint;
        steer(destination, predicted);
        fight(predicted);
        inputActive = true;
        behaviorTicks++;
        if (fireworkCooldown > 0) {
            fireworkCooldown--;
        }
    }

    private double[] desiredPosition(double[] predicted) {
        double x = predicted[0];
        double y = predicted[1] + followGroundHeight.get();
        double z = predicted[2];
        if (mode.is("Mace")) {
            y = predicted[1] + Math.max(maceMinFollowHeight.get(), maceHeight.get()) + maceYBias.get();
            if (behaviorTicks > macePullUpTicks.asInt()) {
                y = predicted[1] + maceGroundHeight.get() * 0.25;
            }
        } else if (mode.is("Spear")) {
            y = predicted[1] + 1.0;
        }
        double dx = x - state().x();
        double dz = z - state().z();
        double distance = Math.hypot(dx, dz);
        if (mode.is("Follow") && distance < stopDistance.get()) {
            return new double[]{state().x(), state().y(), state().z()};
        }
        return new double[]{x, y, z};
    }

    private void steer(double[] destination, double[] predicted) {
        double dx = destination[0] - state().x();
        double dy = destination[1] - state().y();
        double dz = destination[2] - state().z();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.05) {
            return;
        }
        double speed = Math.min(maxFlightSpeed.get(), length);
        if (mode.is("Spear") && behaviorTicks >= spearPullOverTicks.asInt()) {
            speed = Math.min(spearLungeStrength.get(), length);
        }
        if (maceSmoothFlight.get() && mode.is("Mace")) {
            speed *= 0.75;
        }
        if (controlMode.is("DirectVelocity")) {
            actions().setMotion(dx / length * speed, dy / length * speed, dz / length * speed);
        }
        float yaw = dev.nocturne.client.control.RotationController.yawTo(dx, dz);
        float pitch = dev.nocturne.client.control.RotationController.pitchTo(Math.hypot(dx, dz), dy);
        if (mode.is("Mace") && maceAngleOptimize.get()) {
            pitch = Math.min(90.0f, maceDownAngle.asFloat());
        }
        client().rotations().request(yaw, pitch, 8, 150_000_000L);
        if (autoControlElytra.get() && !Boolean.TRUE.equals(bridge().callMapped(bridge().player(),
                dev.nocturne.client.mapping.ClassType.LIVING_ENTITY, "isFallFlying"))) {
            actions().sendPlayerCommand("START_FALL_FLYING");
        }
        if (allowFirework.get() && fireworkCooldown == 0 && length > 8.0) {
            int rocket = EpsilonSupport.findHotbar(bridge(), "firework_rocket");
            if (rocket >= 0) {
                int original = state().selectedSlot();
                actions().selectSlot(rocket, true);
                actions().useItem(true);
                actions().selectSlot(original, true);
                fireworkCooldown = 20;
            }
        }
    }

    private void fight(double[] predicted) {
        double distance = Math.sqrt(Math.pow(predicted[0] - state().x(), 2)
                + Math.pow(predicted[1] - state().y(), 2) + Math.pow(predicted[2] - state().z(), 2));
        if (mode.is("Mace") && distance <= maceRange.get()
                && state().attackStrengthScale() >= maceAttackThreshold.get()) {
            String weapon = maceAntiShield.get() && bridge().usingItem(target.entity()) ? "axe" : "mace";
            int slot = EpsilonSupport.findHotbar(bridge(), weapon);
            if (slot >= 0) {
                actions().selectSlot(slot, true);
            }
            if (actions().attack(target.entity())) {
                actions().swing(false, !maceSwingHand.get());
            }
        } else if (mode.is("Spear") && distance <= spearRange.get() && distance >= spearMinRange.get()) {
            int slot = EpsilonSupport.findHotbar(bridge(), "spear", "trident");
            if (slot >= 0) {
                actions().selectSlot(slot, true);
            }
            if (spearAntiSpear.get() && bridge().usingItem(target.entity())) {
                actions().setMotion(0.0, 0.2, spearAntiExtraDistance.get() * 0.1);
            }
            if (behaviorTicks % spearPullOverTicks.asInt() == 0 && actions().attack(target.entity())) {
                if (logSpearHit.get()) {
                    System.out.println("[nocturne] ElytraCombat spear hit " + bridge().entityName(target.entity()));
                }
            }
        }
    }

    private void recordHistory(TargetManager.Target current) {
        int id = bridge().entityId(current.entity());
        Deque<double[]> samples = history.get(id);
        if (samples == null) {
            samples = new ArrayDeque<double[]>();
            history.put(id, samples);
        }
        samples.addLast(new double[]{current.x(), current.y(), current.z()});
        while (samples.size() > predictionHistory.asInt()) {
            samples.removeFirst();
        }
    }

    private double[] predict(TargetManager.Target current) {
        double[] base = {current.x(), current.y(), current.z()};
        if (mode.is("Mace") && !maceUsePredictor.get() || mode.is("Spear") && !spearUsePredictor.get()) {
            return base;
        }
        Deque<double[]> samples = history.get(bridge().entityId(current.entity()));
        if (samples == null || samples.size() < 2) {
            return base;
        }
        double[] first = samples.peekFirst();
        double[] last = samples.peekLast();
        int count = samples.size() - 1;
        double ticks = predictTicks.get();
        for (int axis = 0; axis < 3; axis++) {
            double velocity = (last[axis] - first[axis]) / count;
            double acceleration = 0.0;
            if (predictor.is("Quadratic") && samples.size() >= 3) {
                double[] previous = null;
                double previousVelocity = 0.0;
                for (double[] sample : samples) {
                    if (previous != null) {
                        double currentVelocity = sample[axis] - previous[axis];
                        acceleration = currentVelocity - previousVelocity;
                        previousVelocity = currentVelocity;
                    }
                    previous = sample;
                }
            }
            double factor = predictor.is("NV") ? 1.15 : 1.0;
            base[axis] += velocity * ticks * factor + acceleration * ticks * ticks * 0.5;
        }
        return base;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!renderVector.get() || target == null) {
            return;
        }
        WorldProjection.ScreenPoint from = projection.project(state().x(), state().y() + 1.62, state().z());
        WorldProjection.ScreenPoint to = projection.project(target.x(), target.aimY(), target.z());
        if (from.visible && to.visible) {
            draw.line(from.x, from.y, to.x, to.y, renderWidth.asFloat(), renderColor.argb());
        }
    }

    public boolean controllingCombat() {
        return isEnabled() && target != null && (mode.is("Mace") || mode.is("Spear"));
    }

    private void resetBehavior() {
        waypoint = null;
        behaviorTicks = 0;
        pathCooldown = 0;
    }

    @Override
    protected void onStop() {
        target = null;
        history.clear();
        inputActive = false;
        resetBehavior();
    }
}
