package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MotionEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Elytra Grim Acc：回滚真实位移并发送受控伪造位置以触发 Grim 拉回加速。 */
public final class ElytraGrimAccelerateModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Simulation", "Simulation", "CrashPackets"));
    private final NumberValue maxVelocity = add(new NumberValue("Max Accelerate Velocity", 6, 0, 100, 0.5));
    private final NumberValue setbackWait = add(new NumberValue("Setback Wait", 20, 1, 100, 1));
    private final BooleanValue fixOldVelocity = add(new BooleanValue("Fix Old Version Velocity", false));
    private final BooleanValue fixKick = add(new BooleanValue("Fix Kick From Lag", true));
    private double startX;
    private double startY;
    private double startZ;
    private int wait;
    private int cycle;

    public ElytraGrimAccelerateModule() {
        super("Elytra Grim Acc", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, event -> {
            if (event.direction == PacketEvent.Direction.RECEIVE
                    && bridge().isInstance(event.packet, ClassType.CLIENTBOUND_PLAYER_POSITION)) {
                wait = 0;
            }
        });
        listen(MotionEvent.class, event -> {
            if (!event.pre) {
                return;
            }
            double[] velocity = bridge().entityVelocity(bridge().player());
            double horizontal = Math.hypot(velocity[0], velocity[2]);
            boolean flying = Boolean.TRUE.equals(bridge().callMapped(bridge().player(),
                    ClassType.LIVING_ENTITY, "isFallFlying"));
            if (!flying || state().onGround() || horizontal > maxVelocity.get()
                    || fixOldVelocity.get() && horizontal > 3.8 || wait-- > 0) {
                return;
            }
            event.x = startX;
            event.y = startY;
            event.z = startZ;
            double[] offsets = {2.5, 5.0, 7.5};
            if (mode.is("CrashPackets")) {
                actions().sendPositionRotation(3.9999999E7, startY, 3.9999999E7,
                        state().yaw(), state().pitch(), state().onGround());
            } else {
                actions().sendPositionRotation(startX, startY + offsets[cycle++ % offsets.length], startZ,
                        state().yaw(), state().pitch(), state().onGround());
            }
            wait = setbackWait.asInt();
        });
    }

    @Override
    public void onTick() {
        startX = state().x();
        startY = state().y();
        startZ = state().z();
        if (fixKick.get() && wait > 0) {
            actions().sendGround(state().onGround());
        }
    }

    @Override
    protected void onStop() {
        wait = 0;
    }
}
