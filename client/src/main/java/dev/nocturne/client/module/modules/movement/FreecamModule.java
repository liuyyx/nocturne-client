package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.event.ScrollEvent;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon Free Camera：自由移动、滚轮调速、潜行保持、伤害/死亡/退服停用和选项恢复。 */
public final class FreecamModule extends GameModule {
    private final NumberValue speed = add(new NumberValue("Speed", 1.0, 0.1, 10, 0.1));
    private final NumberValue scrollSensitivity = add(new NumberValue("Speed Scroll Sensitivity", 0, 0, 2, 0.1));
    private final BooleanValue staySneaking = add(new BooleanValue("Stay Sneaking", true));
    private final BooleanValue toggleOnDamage = add(new BooleanValue("Toggle On Damage", false));
    private final BooleanValue toggleOnDeath = add(new BooleanValue("Toggle On Death", false));
    private final BooleanValue toggleOnLog = add(new BooleanValue("Toggle On Log", true));
    private final BooleanValue reloadChunks = add(new BooleanValue("Reload Chunks", true));
    private final BooleanValue showHands = add(new BooleanValue("Show Hands", true));
    private final BooleanValue rotate = add(new BooleanValue("Rotate", false));
    private final BooleanValue staticView = add(new BooleanValue("Static", true));
    private float forward;
    private float strafe;
    private boolean jump;
    private boolean sneak;
    private float health;
    private float originalFovEffect;
    private boolean originalBob;
    private boolean staticApplied;

    public FreecamModule() {
        super("Free Camera", Category.RENDER);
    }

    @Override
    protected void onStart() {
        health = state().health();
        listen(MovementInputEvent.class, event -> {
            forward = event.forward;
            strafe = event.strafe;
            jump = event.jump;
            sneak = event.sneak || staySneaking.get();
        });
        listen(MoveEvent.class, event -> {
            double yaw = Math.toRadians(state().yaw());
            event.x = (strafe * Math.cos(yaw) - forward * Math.sin(yaw)) * speed.get();
            event.z = (forward * Math.cos(yaw) + strafe * Math.sin(yaw)) * speed.get();
            event.y = jump ? speed.get() : sneak ? -speed.get() : 0.0;
        });
        listen(PacketEvent.class, event -> {
            if (event.direction == PacketEvent.Direction.SEND
                    && bridge().isInstance(event.packet, ClassType.SERVERBOUND_MOVE_PLAYER)) {
                event.cancelled = true;
            }
        });
        listen(ScrollEvent.class, event -> {
            if (scrollSensitivity.get() > 0.0) {
                speed.set(Math.max(0.1, Math.min(10.0, speed.get() + event.delta * scrollSensitivity.get())));
            }
        });
        listen(WorldChangeEvent.class, event -> {
            if (toggleOnLog.get()) {
                setEnabled(false);
            }
        });
        if (bridge() != null) {
            if (reloadChunks.get()) {
                bridge().reloadChunks();
            }
            applyStatic();
        }
    }
    @Override
    public void onTick() {
        float current = state().health();
        if (toggleOnDeath.get() && current <= 0.0f || toggleOnDamage.get() && current < health) {
            setEnabled(false);
            return;
        }
        health = current;
        if (rotate.get()) {
            Object hit = actions().pick(256.0, 1.0f);
            Object location = bridge().hitLocation(hit);
            double[] pos = bridge().entityPosition(bridge().player());
            Number x = number(bridge().readField(location, ClassType.VEC3, "x"));
            Number y = number(bridge().readField(location, ClassType.VEC3, "y"));
            Number z = number(bridge().readField(location, ClassType.VEC3, "z"));
            if (pos != null && x != null && y != null && z != null) {
                EpsilonSupport.aim(bridge(), state(), x.doubleValue(), y.doubleValue(), z.doubleValue(), 180, 9);
            }
        }
    }

    private void applyStatic() {
        if (!staticView.get()) {
            return;
        }
        Object options = bridge().readField(bridge().minecraft(), ClassType.MINECRAFT, "options");
        Number fov = number(bridge().callMapped(bridge().readField(options, ClassType.OPTIONS, "fovEffectScale"),
                ClassType.OPTION_INSTANCE, "get"));
        Object bob = bridge().callMapped(bridge().readField(options, ClassType.OPTIONS, "bobView"),
                ClassType.OPTION_INSTANCE, "get");
        originalFovEffect = fov == null ? 1.0f : fov.floatValue();
        originalBob = !(bob instanceof Boolean) || (Boolean) bob;
        staticApplied = bridge().writeOption("fovEffectScale", 0.0)
                | bridge().writeOption("bobView", false);
    }

    public boolean renderHands() {
        return showHands.get();
    }

    @Override
    protected void onStop() {
        if (staticApplied && bridge() != null) {
            bridge().writeOption("fovEffectScale", (double) originalFovEffect);
            bridge().writeOption("bobView", originalBob);
        }
        if (reloadChunks.get() && bridge() != null) {
            bridge().reloadChunks();
        }
        staticApplied = false;
        forward = 0.0f;
        strafe = 0.0f;
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }
}
