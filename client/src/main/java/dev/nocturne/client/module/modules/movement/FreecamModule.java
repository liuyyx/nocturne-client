package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.event.UseItemEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** 客户端自由相机：只拦截移动包，停用恢复真实位置。 */
public final class FreecamModule extends GameModule {
    private final BooleanValue allowInteracting = add(new BooleanValue("Allow Interacting", true));
    private final NumberValue speed = add(new NumberValue("Speed", 3.0, 1.0, 5.0, 0.1));
    private final NumberValue verticalSpeed = add(new NumberValue("Vertical Speed", 3.0, 1.0, 5.0, 0.1));
    private final NumberValue range = add(new NumberValue("Range", 0.15, 0.05, 1.0, 0.05));
    private double originalX;
    private double originalY;
    private double originalZ;
    private float originalYaw;
    private float originalPitch;
    private boolean originalNoPhysics;
    private float forward;
    private float strafe;
    private boolean jump;
    private boolean sneak;

    public FreecamModule() {
        super("Freecam", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        PlayerState state = state();
        if (!state.present() || bridge() == null) {
            return;
        }
        originalX = state.x();
        originalY = state.y();
        originalZ = state.z();
        originalYaw = state.yaw();
        originalPitch = state.pitch();
        Object noPhysics = bridge().readField(state.player(), ClassType.ENTITY, "noPhysics");
        originalNoPhysics = Boolean.TRUE.equals(noPhysics);
        bridge().setNoPhysics(state.player(), true);
        listen(MovementInputEvent.class, event -> {
            forward = event.forward;
            strafe = event.strafe;
            jump = event.jump;
            sneak = event.sneak;
        });
        listen(MoveEvent.class, this::onMove);
        listen(PacketEvent.class, this::onPacket);
        listen(AttackEvent.class, event -> {
            if (!allowInteracting.get()) event.cancelled = true;
        });
        listen(UseItemEvent.class, event -> {
            if (!allowInteracting.get()) event.cancelled = true;
        });
    }

    private void onMove(MoveEvent event) {
        PlayerState state = state();
        double radians = Math.toRadians(state.yaw());
        double factor = speed.get() * range.get();
        event.x = (strafe * Math.cos(radians) - forward * Math.sin(radians)) * factor;
        event.z = (forward * Math.cos(radians) + strafe * Math.sin(radians)) * factor;
        event.y = jump ? verticalSpeed.get() * range.get()
                : sneak ? -verticalSpeed.get() * range.get() : 0.0;
    }

    private void onPacket(PacketEvent event) {
        if (event.direction == PacketEvent.Direction.SEND
                && bridge().isInstance(event.packet, ClassType.SERVERBOUND_MOVE_PLAYER)) {
            event.cancelled = true;
        }
    }

    @Override
    protected void onStop() {
        GameBridge bridge = bridge();
        if (bridge != null && bridge.player() != null) {
            bridge.setPosition(bridge.player(), originalX, originalY, originalZ);
            bridge.setNoPhysics(bridge.player(), originalNoPhysics);
            if (actions() != null) {
                actions().setRotation(originalYaw, originalPitch);
            }
        }
        forward = 0.0f;
        strafe = 0.0f;
        jump = false;
        sneak = false;
    }
}
