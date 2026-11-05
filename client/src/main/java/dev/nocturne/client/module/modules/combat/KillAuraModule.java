package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.render.ParticleOverlay;
import dev.nocturne.client.module.modules.render.WorldGeometry;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.List;

/** Epsilon Kill Aura：单/切换目标、1.8/1.9+、Normal/Polar/Ai 旋转与四种 ESP。 */
public final class KillAuraModule extends GameModule implements WorldOverlay {
    private final BooleanValue pauseOnEat = bool("Pause On Eat", true);
    private final BooleanValue pauseOnScaffold = bool("Pause On Scaffold", true);
    private final BooleanValue hitSelect = bool("Hit Select", true);
    private final ModeValue mode = mode("Mode", "1.8", "1.8", "1.9+");
    private final ModeValue targetMode = mode("Target Mode", "Single", "Single", "Switch");
    private final NumberValue switchDelay = number("Switch Delay", 100, 0, 500, 10);
    private final ModeValue priorityMode = mode("Priority Mode", "None", "None", "Health", "Fov", "Range");
    private final NumberValue searchRange = number("Search Range", 4, 1, 6, 0.1);
    private final NumberValue aimRange = number("Aim Range", 3, 1, 6, 0.1);
    private final NumberValue fov = number("FOV", 360, 10, 360, 10);
    private final NumberValue rotationSpeed = number("Rotation Speed", 180, 10, 180, 1);
    private final NumberValue rotationPriority = number("Rotation Priority", 6, 1, 9, 1);
    private final ModeValue aimMode = mode("Aim Mode", "Normal", "Normal", "Polar", "Ai");
    private final NumberValue polarYawMin = number("Polar Yaw Accel Min", 22, 1, 180, 1);
    private final NumberValue polarYawMax = number("Polar Yaw Accel Max", 25, 1, 180, 1);
    private final NumberValue polarPitchMin = number("Polar Pitch Accel Min", 15, 1, 90, 1);
    private final NumberValue polarPitchMax = number("Polar Pitch Accel Max", 17, 1, 90, 1);
    private final NumberValue polarClickMin = number("Polar Click Min CPS", 9, 1, 20, 1);
    private final NumberValue polarClickMax = number("Polar Click Max CPS", 11, 1, 20, 1);
    private final BooleanValue polarIgnoreCooldown = bool("Polar Ignore Attack Cooldown", true);
    private final NumberValue polarCooldownMin = number("Polar Cooldown Min", 0.15, 0, 1, 0.01);
    private final NumberValue polarCooldownMax = number("Polar Cooldown Max", 0.36, 0, 1, 0.01);
    private final ModeValue aiModel = mode("AI Model", "21KC11KP", "21KC11KP", "19KC8KP");
    private final NumberValue aiYawMultiplier = number("AI Yaw Multiplier", 1.5, 0.5, 2, 0.1);
    private final NumberValue aiPitchMultiplier = number("AI Pitch Multiplier", 1, 0.5, 2, 0.1);
    private final NumberValue aiMaxYaw = number("AI Max Yaw Step", 15, 1, 180, 1);
    private final NumberValue aiMaxPitch = number("AI Max Pitch Step", 10, 1, 90, 1);
    private final NumberValue cps = number("CPS", 12, 1, 20, 1);
    private final BooleanValue players = bool("Players", true);
    private final BooleanValue mobs = bool("Mobs", true);
    private final BooleanValue animals = bool("Animals", true);
    private final BooleanValue villagers = bool("Villagers", false);
    private final BooleanValue ambient = bool("Ambient", false);
    private final BooleanValue water = bool("Water", false);
    private final BooleanValue others = bool("Others", false);
    private final BooleanValue invisible = bool("Invisible", true);
    private final BooleanValue swingHand = bool("SwingHand", true);
    private final BooleanValue esp = bool("ESP", true);
    private final ModeValue espMode = mode("ESP Mode", "Circle", "CaptureMark", "Circle", "Firefly", "Deobf");
    private final ModeValue deobfMode = mode("Deobf Mode", "Mengcha", "Mengcha");
    private final NumberValue deobfSize = number("Size", 0.75, 0.1, 3, 0.05);
    private final NumberValue deobfSpins = number("Spins", 3, 0.1, 10, 0.1);
    private final NumberValue deobfWobble = number("Wobble", 1, 0, 5, 0.1);
    private final NumberValue deobfFlyHeight = number("Fly Height", 5, 0, 20, 0.5);
    private final ColorValue espMain = color("ESP Main", 0xFFFFB7C5);
    private final ColorValue espSecond = color("ESP Second", 0xFFFF85A1);
    private final NumberValue espSize = number("ESP Size", 1.2, 0.1, 3, 0.1);
    private final NumberValue rotSpeed = number("Rot Speed", 2, 0.1, 10, 0.1);
    private final NumberValue waveSpeed = number("Wave Speed", 3, 0.1, 10, 0.1);
    private final ColorValue sideColor = color("Side Color", 0xFFFFFFFF);
    private final ColorValue lineColor = color("Line Color", 0xE9FFFFFF);
    private final NumberValue circleRadius = number("Circle Radius", 0.75, 0.1, 3, 0.05);
    private final NumberValue circleAlpha = number("Circle Alpha Factor", 1, 0, 1, 0.05);
    private final ModeValue fireflyColorMode = mode("ColorMode", "Blend", "Blend", "Custom", "Rainbow");
    private final ColorValue fireflyColor = color("Color", 0xFF959595);
    private final ColorValue fireflyColor2 = color("Color2", 0xFFFF85A1);
    private final NumberValue fireflyMix = number("Mix", 0.65, 0, 1, 0.05);
    private final NumberValue colorSpeed = number("ColorSpeed", 1.2, 0, 10, 0.1);
    private final NumberValue rainbowSpeed = number("Rainbow Speed", 1, 0, 10, 0.1);
    private final NumberValue saturation = number("Saturation", 0.85, 0, 1, 0.05);
    private final NumberValue brightness = number("Brightness", 1, 0, 1, 0.05);
    private final NumberValue fireflyLength = number("Length", 14, 8, 128, 1);
    private final NumberValue fireflyFactor = number("Factor", 8, 1, 10, 1);
    private final NumberValue fireflyShaking = number("Shaking", 1.8, 0, 10, 0.1);
    private final NumberValue fireflyAmplitude = number("Amplitude", 3, 0, 10, 0.1);
    private final List<ParticleOverlay.Particle> fireflies = new ArrayList<ParticleOverlay.Particle>();
    private TargetManager.Target target;
    private long lastSwitch;
    private long nextAttack;
    private float yawVelocity;
    private float pitchVelocity;

    public KillAuraModule() {
        super("Kill Aura", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(WorldChangeEvent.class, event -> clear());
    }

    @Override
    public void onTick() {
        if (pauseOnEat.get() && bridge().usingItem(bridge().player())
                || pauseOnScaffold.get() && EpsilonSupport.enabled("Scaffold")) {
            return;
        }
        TargetManager.Options options = EpsilonSupport.targets(searchRange.get(), players.get(),
                mobs.get() || animals.get() || villagers.get() || ambient.get() || water.get() || others.get(),
                invisible.get(), sort());
        options.maxAngle = fov.get();
        List<TargetManager.Target> targets = client().targets().list(client(), options);
        if (target == null || !containsEntity(targets, target) || targetMode.is("Switch")
                && System.currentTimeMillis() - lastSwitch >= switchDelay.asInt()) {
            target = targets.isEmpty() ? null : targets.get(0);
            lastSwitch = System.currentTimeMillis();
        }
        if (target == null) {
            fireflies.clear();
            return;
        }
        aim(target);
        attack(target);
        if (esp.get() && espMode.is("Firefly")) {
            spawnFireflies(target);
        }
        ParticleOverlay.tick(fireflies);
    }

    private void aim(TargetManager.Target current) {
        double dx = current.x() - state().x();
        double dz = current.z() - state().z();
        double horizontal = Math.hypot(dx, dz);
        float targetYaw = dev.nocturne.client.control.RotationController.yawTo(dx, dz);
        float targetPitch = dev.nocturne.client.control.RotationController.pitchTo(horizontal,
                current.aimY() - state().y() - 1.62);
        float yawStep = rotationSpeed.asFloat();
        float pitchStep = rotationSpeed.asFloat();
        if (aimMode.is("Polar")) {
            yawVelocity += dev.nocturne.client.control.RotationController.wrap(targetYaw - state().yaw())
                    * EpsilonSupport.randomInt(polarYawMin.asInt(), polarYawMax.asInt()) / 100.0f;
            pitchVelocity += (targetPitch - state().pitch())
                    * EpsilonSupport.randomInt(polarPitchMin.asInt(), polarPitchMax.asInt()) / 100.0f;
            yawVelocity *= 0.65f;
            pitchVelocity *= 0.65f;
            yawStep = Math.abs(yawVelocity);
            pitchStep = Math.abs(pitchVelocity);
        } else if (aimMode.is("Ai")) {
            double model = aiModel.is("21KC11KP") ? 0.83 : 0.67;
            yawStep = (float) Math.min(aiMaxYaw.get(), Math.abs(dev.nocturne.client.control.RotationController.wrap(
                    targetYaw - state().yaw())) * aiYawMultiplier.get() * model);
            pitchStep = (float) Math.min(aiMaxPitch.get(), Math.abs(targetPitch - state().pitch())
                    * aiPitchMultiplier.get() * model);
        }
        client().rotations().request(dev.nocturne.client.control.RotationController.approach(
                        state().yaw(), targetYaw, yawStep),
                dev.nocturne.client.control.RotationController.approach(state().pitch(), targetPitch, pitchStep),
                rotationPriority.asInt(), 150_000_000L);
    }

    private void attack(TargetManager.Target current) {
        long now = System.currentTimeMillis();
        if (current.distance() > aimRange.get() || now < nextAttack
                || hitSelect.get() && bridge().hurtTime(current.entity()) > 0) {
            return;
        }
        if (mode.is("1.9+") && !(aimMode.is("Polar") && polarIgnoreCooldown.get())
                && state().attackStrengthScale() < 1.0f) {
            return;
        }
        if (actions().attack(current.entity())) {
            actions().swing(false, !swingHand.get());
            double min = aimMode.is("Polar") ? polarClickMin.get() : cps.get();
            double max = aimMode.is("Polar") ? polarClickMax.get() : cps.get();
            double cooldown = EpsilonSupport.randomLong((long) min, (long) max);
            long delay = (long) (1000.0 / Math.max(1.0, cooldown));
            if (aimMode.is("Polar")) {
                delay = (long) (EpsilonSupport.randomLong((long) (polarCooldownMin.get() * 1000),
                        (long) (polarCooldownMax.get() * 1000)));
            }
            nextAttack = now + delay;
        }
    }

    private static boolean containsEntity(List<TargetManager.Target> targets, TargetManager.Target target) {
        for (TargetManager.Target candidate : targets) {
            if (candidate.entity() == target.entity()) {
                return true;
            }
        }
        return false;
    }

    private TargetManager.Sort sort() {
        return priorityMode.is("Health") ? TargetManager.Sort.HEALTH
                : priorityMode.is("Range") ? TargetManager.Sort.DISTANCE : TargetManager.Sort.ANGLE;
    }

    private void spawnFireflies(TargetManager.Target current) {
        while (fireflies.size() < fireflyLength.asInt()) {
            int color = fireflyColorMode.is("Rainbow")
                    ? java.awt.Color.HSBtoRGB((float) (System.currentTimeMillis() * 0.001 * rainbowSpeed.get()),
                    saturation.asFloat(), brightness.asFloat()) | 0xFF000000
                    : blend(fireflyColor.argb(), fireflyColor2.argb(), fireflyMix.asFloat());
            ParticleOverlay.emit(fireflies, current.x() + (Math.random() - 0.5) * fireflyShaking.get(),
                    current.aimY() + (Math.random() - 0.5) * fireflyAmplitude.get(),
                    current.z() + (Math.random() - 0.5) * fireflyShaking.get(),
                    fireflyFactor.get() * 0.1, 1.0, color, "•", "Fly");
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!esp.get() || target == null) {
            return;
        }
        if (espMode.is("Firefly")) {
            ParticleOverlay.draw(fireflies, draw, projection, 2.0f);
            return;
        }
        double y = target.y();
        if (espMode.is("Deobf")) {
            y += Math.sin(System.currentTimeMillis() * 0.001 * waveSpeed.get()) * deobfWobble.get()
                    + deobfFlyHeight.get() * 0.1;
            WorldGeometry.box(draw, projection, target.x() - deobfSize.get(), y,
                    target.z() - deobfSize.get(), target.x() + deobfSize.get(), y + deobfSize.get(),
                    target.z() + deobfSize.get(), rotSpeed.asFloat() * 0.5f, espSecond.argb());
        } else {
            int color = espMode.is("CaptureMark") ? espMain.argb() : lineColor.argb();
            if (espMode.is("Circle")) {
                color = color & 0xFFFFFF | (int) ((color >>> 24) * circleAlpha.get()) << 24;
            }
            WorldGeometry.circle(draw, projection, target.x(), y, target.z(),
                    espMode.is("Circle") ? circleRadius.get() : espSize.get(), 48, 1.0f, color);
            if (espMode.is("CaptureMark")) {
                WorldGeometry.label(draw, projection, "◆", target.x(), target.aimY(), target.z(), espSecond.argb());
            }
            if (espMode.is("Circle")) {
                WorldGeometry.label(draw, projection, "", target.x(), y, target.z(), sideColor.argb());
            }
        }
    }

    private static int blend(int first, int second, float amount) {
        int r = (int) ((first >> 16 & 255) * (1 - amount) + (second >> 16 & 255) * amount);
        int g = (int) ((first >> 8 & 255) * (1 - amount) + (second >> 8 & 255) * amount);
        int b = (int) ((first & 255) * (1 - amount) + (second & 255) * amount);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    public Object currentTarget() {
        return target == null ? null : target.entity();
    }

    private void clear() {
        target = null;
        fireflies.clear();
        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;
    }

    @Override
    protected void onStop() {
        clear();
        client().rotations().release(rotationPriority.asInt());
    }

    private BooleanValue bool(String name, boolean def) { return add(new BooleanValue(name, def)); }
    private NumberValue number(String name, double def, double min, double max, double step) {
        return add(new NumberValue(name, def, min, max, step));
    }
    private ModeValue mode(String name, String def, String... options) { return add(new ModeValue(name, def, options)); }
    private ColorValue color(String name, int def) { return add(new ColorValue(name, def)); }
}
