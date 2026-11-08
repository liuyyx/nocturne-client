package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon Speed：Strafe/StrafeStrict/Grim、击退加速与船移动参数。 */
public final class SpeedModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Strafe", "Strafe", "StrafeStrict", "Grim"));
    private final NumberValue collideSpeed = add(new NumberValue("CollideSpeed", 0.08, 0, 0.08, 0.01));
    private final BooleanValue strict = add(new BooleanValue("Strict", true));
    private final BooleanValue boat = add(new BooleanValue("BoatLongJump", true));
    private final NumberValue boatExpand = add(new NumberValue("BoatExpand", 0.2, 0, 1, 0.01));
    private final NumberValue boatSpeed = add(new NumberValue("BoatSpeed", 0.2, -2, 2, 0.01));
    private final NumberValue boatJump = add(new NumberValue("BoatJump", 0.2, 0, 2, 0.01));
    private final BooleanValue inWater = add(new BooleanValue("InWater", false));
    private final BooleanValue inBlock = add(new BooleanValue("InBlock", false));
    private final BooleanValue airStop = add(new BooleanValue("AirStop", true));
    private final NumberValue lagTime = add(new NumberValue("LagTime", 500, 0, 1000, 1));
    private final BooleanValue jump = add(new BooleanValue("Jump", true));
    private final NumberValue strafeSpeed = add(new NumberValue("Speed", 0.2873, 0, 1, 0.0001));
    private final BooleanValue explosions = add(new BooleanValue("ExplosionsBoost", false));
    private final BooleanValue velocity = add(new BooleanValue("VelocityBoost", true));
    private final NumberValue horizontalFactor = add(new NumberValue("H-Factor", 1, 0, 5, 0.01));
    private final NumberValue verticalFactor = add(new NumberValue("V-Factor", 1, 0, 5, 0.01));
    private final NumberValue cooldown = add(new NumberValue("Cooldown", 1000, 0, 5000, 1));
    private final BooleanValue slowness = add(new BooleanValue("Slowness", false));
    private double pendingBoost;
    private long nextBoost;
    private long lastPacket;

    public SpeedModule() {
        super("Speed", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, event -> {
            if (event.direction != PacketEvent.Direction.RECEIVE) {
                return;
            }
            lastPacket = System.currentTimeMillis();
            int[] data = bridge().velocityData(event.packet);
            if (data != null && (velocity.get() || explosions.get()) && System.currentTimeMillis() >= nextBoost) {
                pendingBoost = Math.hypot(data[1], data[3]) / 8000.0;
                nextBoost = System.currentTimeMillis() + cooldown.asInt();
            }
        });
        listen(MoveEvent.class, this::move);
    }

    private void move(MoveEvent event) {
        if (!inWater.get() && bridge().inWater(bridge().player())
                || System.currentTimeMillis() - lastPacket < lagTime.get()) {
            return;
        }
        boolean passenger = Boolean.TRUE.equals(bridge().callMapped(bridge().player(), ClassType.ENTITY, "isPassenger"));
        if (passenger && mode.is("Grim")) {
            event.x *= 1.0 + boatExpand.get() + boatSpeed.get();
            event.z *= 1.0 + boatExpand.get() + boatSpeed.get();
            if (boat.get() && state().onGround()) {
                event.y = Math.max(event.y, boatJump.get());
            }
            return;
        }
        if (airStop.get() && !state().onGround() && !state().moving()) {
            event.x = 0.0;
            event.z = 0.0;
            return;
        }
        double yaw = Math.toRadians(state().yaw());
        double speed = mode.is("Grim") ? collideSpeed.get() : strafeSpeed.get();
        speed = speed * horizontalFactor.get() + pendingBoost;
        pendingBoost = 0.0;
        if (slowness.get() && bridge().hasEffect("SLOWNESS")) {
            speed *= 0.8;
        }
        if (!mode.is("Strafe") || state().moving()) {
            event.x = -Math.sin(yaw) * speed;
            event.z = Math.cos(yaw) * speed;
        }
        if (mode.is("StrafeStrict")) {
            double length = Math.hypot(event.x, event.z);
            if (length > speed) {
                event.x *= speed / length;
                event.z *= speed / length;
            }
        }
        if (jump.get() && mode.is("Strafe") && state().onGround()) {
            event.y = 0.42 * verticalFactor.get();
        }
        if (mode.is("Grim") && strict.get()) {
            event.y = Math.min(event.y, 0.0);
        }
        if (!inBlock.get() && !bridge().isAir(bridge().blockStateAt((int) Math.floor(state().x()),
                (int) Math.floor(state().y()), (int) Math.floor(state().z())))) {
            event.x = 0.0;
            event.z = 0.0;
        }
    }

    @Override
    protected void onStop() {
        pendingBoost = 0.0;
    }
}
