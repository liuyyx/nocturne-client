package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Auto Map：沿可保存的阿基米德螺线自动飞行探索。 */
public final class AutoMapModule extends GameModule {
    private final BooleanValue autoLaunch = add(new BooleanValue("Auto Launch", true));
    private final NumberValue spiralSpacing = add(new NumberValue("Spiral Spacing", 192, 16, 1024, 1));
    private final NumberValue pointsPerLap = add(new NumberValue("Points Per Lap", 16, 4, 96, 1));
    private final NumberValue arrivalDistance = add(new NumberValue("Arrival Distance", 8, 2, 64, 0.5));
    private final NumberValue rotationSpeed = add(new NumberValue("Rotation Speed", 180, 10, 180, 10));
    private final NumberValue takeoffHeight = add(new NumberValue("Takeoff Height", 192, 16, 1024, 1));
    private final BooleanValue takeoffFirework = add(new BooleanValue("Takeoff Firework", true));
    private final NumberValue saveInterval = add(new NumberValue("Save Interval", 100, 20, 1200, 20));
    private final BooleanValue writeRecord = add(new BooleanValue("Write Record", false));
    private final BooleanValue resetRecord = add(new BooleanValue("Reset Record", false));
    private final Path recordPath = Paths.get(System.getProperty("user.home"), ".nocturne", "automap.record");
    private double centerX;
    private double centerZ;
    private double angle;
    private double radius;
    private int saveTicks;

    public AutoMapModule() {
        super("Auto Map", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        load();
        listen(MovementInputEvent.class, event -> {
            event.forward = 1.0f;
            event.strafe = 0.0f;
            if (autoLaunch.get() && !fallFlying() && !bridge().inWater(bridge().player())) {
                event.jump = true;
            }
        });
    }

    @Override
    public void onTick() {
        if (writeRecord.get()) {
            writeRecord.set(false);
            save();
        }
        if (resetRecord.get()) {
            resetRecord.set(false);
            centerX = state().x();
            centerZ = state().z();
            angle = 0.0;
            radius = 0.0;
            save();
        }
        double[] target = target();
        EpsilonSupport.aim(bridge(), state(), target[0], target[1], target[2], rotationSpeed.asFloat(), 7);
        double distance = Math.hypot(target[0] - state().x(), target[2] - state().z());
        if (distance <= arrivalDistance.get()) {
            angle += Math.PI * 2.0 / pointsPerLap.asInt();
            radius = spiralSpacing.get() * angle / (Math.PI * 2.0);
        }
        if (autoLaunch.get()) {
            if (!fallFlying() && state().onGround()) {
                actions().setJumping(true);
            } else if (!fallFlying()) {
                actions().sendPlayerCommand("START_FALL_FLYING");
            }
            if (takeoffFirework.get() && state().y() < takeoffHeight.get()) {
                int rocket = EpsilonSupport.findHotbar(bridge(), "firework_rocket");
                if (rocket >= 0 && saveTicks % 20 == 0) {
                    int original = state().selectedSlot();
                    actions().selectSlot(rocket, true);
                    actions().useItem(true);
                    actions().selectSlot(original, true);
                }
            }
        }
        if (++saveTicks >= saveInterval.asInt()) {
            saveTicks = 0;
            save();
        }
    }

    private double[] target() {
        return new double[]{centerX + Math.cos(angle) * radius, takeoffHeight.get(),
                centerZ + Math.sin(angle) * radius};
    }

    private boolean fallFlying() {
        return Boolean.TRUE.equals(bridge().callMapped(bridge().player(), ClassType.LIVING_ENTITY, "isFallFlying"));
    }

    private void load() {
        try {
            if (!Files.exists(recordPath)) {
                centerX = state().x();
                centerZ = state().z();
                return;
            }
            String[] parts = new String(Files.readAllBytes(recordPath), StandardCharsets.UTF_8).split(",");
            if (parts.length == 4) {
                centerX = Double.parseDouble(parts[0]);
                centerZ = Double.parseDouble(parts[1]);
                angle = Double.parseDouble(parts[2]);
                radius = Double.parseDouble(parts[3]);
            }
        } catch (Exception failure) {
            System.out.println("[nocturne] AutoMap record load failed: " + failure);
        }
    }

    private void save() {
        try {
            Files.createDirectories(recordPath.getParent());
            Files.write(recordPath, (centerX + "," + centerZ + "," + angle + "," + radius)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            System.out.println("[nocturne] AutoMap record save failed: " + failure);
        }
    }

    @Override
    protected void onStop() {
        save();
    }
}
