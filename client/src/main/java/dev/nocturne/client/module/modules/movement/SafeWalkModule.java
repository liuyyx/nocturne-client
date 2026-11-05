package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon Safe Walk：边缘位移裁剪、运动倍率、方向/俯角/按键/持块条件。 */
public final class SafeWalkModule extends GameModule {
    private final NumberValue motion = add(new NumberValue("Motion", 1.0, 0.5, 1.0, 0.05));
    private final NumberValue speedMotion = add(new NumberValue("Speed Motion", 1.0, 0.5, 1.5, 0.05));
    private final BooleanValue air = add(new BooleanValue("Air", false));
    private final BooleanValue directionCheck = add(new BooleanValue("Direction Check", true));
    private final BooleanValue pitchCheck = add(new BooleanValue("Pitch Check", true));
    private final BooleanValue requirePress = add(new BooleanValue("Require Press", false));
    private final BooleanValue blocksOnly = add(new BooleanValue("Blocks Only", true));

    public SafeWalkModule() {
        super("Safe Walk", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, event -> {
            if (allowed(event.forward, true)) {
                event.sneak = true;
            }
        });
        listen(MoveEvent.class, event -> {
            if (!allowed(0.0f, false)) {
                return;
            }
            event.x = clip(event.x, false);
            event.z = clip(event.z, true);
        });
    }

    private double clip(double value, boolean zAxis) {
        if (value == 0.0) {
            return 0.0;
        }
        Object box = bridge().callMapped(bridge().player(), ClassType.ENTITY, "getBoundingBox");
        if (!bridge().noCollision(bridge().player(), bridge().moveBox(box,
                zAxis ? 0.0 : value, -1.0, zAxis ? value : 0.0))) {
            return value;
        }
        double step = Math.signum(value) * 0.05;
        while (Math.abs(value) > 0.05 && bridge().noCollision(bridge().player(),
                bridge().moveBox(box, zAxis ? 0.0 : value, -1.0, zAxis ? value : 0.0))) {
            value -= step;
        }
        return Math.abs(value) <= 0.05 ? 0.0 : value;
    }

    private boolean allowed(float forward, boolean input) {
        if (EpsilonSupport.enabled("Scaffold") || directionCheck.get() && forward > 0.0f
                || pitchCheck.get() && state().pitch() < 69.0f || requirePress.get() && !state().moving()
                || blocksOnly.get() && !holdingBlock() || !air.get() && !state().onGround()) {
            return false;
        }
        return true;
    }

    private boolean holdingBlock() {
        Object item = bridge().callMapped(bridge().heldItem(bridge().player(), false), ClassType.ITEM_STACK, "getItem");
        return bridge().isInstance(item, ClassType.BLOCK_ITEM);
    }

    @Override
    public void onTick() {
        if (!state().moving() || !state().onGround()) {
            return;
        }
        double[] velocity = bridge().entityVelocity(bridge().player());
        float multiplier = String.valueOf(bridge().activeEffects()).toLowerCase(java.util.Locale.ROOT)
                .contains("speed") ? speedMotion.asFloat() : motion.asFloat();
        if (multiplier != 1.0f) {
            actions().setMotion(velocity[0] * multiplier, velocity[1], velocity[2] * multiplier);
        }
    }
}
