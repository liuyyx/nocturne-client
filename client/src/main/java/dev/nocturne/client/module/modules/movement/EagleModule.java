package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** Eagle：带随机延迟、方向/跳跃/俯角/持块条件的边缘潜行。 */
public final class EagleModule extends GameModule {
    private final NumberValue minDelay = add(new NumberValue("Min Delay", 2, 0, 10, 1));
    private final NumberValue maxDelay = add(new NumberValue("Max Delay", 3, 0, 10, 1));
    private final BooleanValue directionCheck = add(new BooleanValue("Direction Check", true));
    private final BooleanValue jumpCheck = add(new BooleanValue("Jump Check", true));
    private final BooleanValue pitchCheck = add(new BooleanValue("Pitch Check", true));
    private final BooleanValue blocksOnly = add(new BooleanValue("Blocks Only", true));
    private final BooleanValue sneakingOnly = add(new BooleanValue("Sneaking Only", false));
    private int sneakDelay;

    public EagleModule() {
        super("Eagle", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, event -> {
            if (bridge().currentScreen() != null) {
                return;
            }
            boolean over = overEdge(event);
            if (sneakDelay == 0 && over) {
                sneakDelay = EpsilonSupport.randomInt(minDelay.asInt(), maxDelay.asInt());
            }
            boolean allowed = !(directionCheck.get() && event.forward > 0.0f)
                    && !(jumpCheck.get() && event.jump) && !(pitchCheck.get() && state().pitch() < 69.0f)
                    && (!blocksOnly.get() || bridge().isInstance(bridge().callMapped(
                    bridge().hotbarStack(state().selectedSlot()), ClassType.ITEM_STACK, "getItem"), ClassType.BLOCK_ITEM))
                    && state().onGround();
            if (sneakingOnly.get() && event.sneak && allowed) {
                event.sneak = false;
            } else if (!event.sneak && allowed && (sneakDelay > 0 || over)) {
                event.sneak = true;
            }
        });
    }

    private boolean overEdge(MovementInputEvent event) {
        double yaw = Math.toRadians(state().yaw());
        double x = event.strafe * Math.cos(yaw) - event.forward * Math.sin(yaw);
        double z = event.forward * Math.cos(yaw) + event.strafe * Math.sin(yaw);
        Object box = bridge().callMapped(bridge().player(), ClassType.ENTITY, "getBoundingBox");
        return bridge().noCollision(bridge().player(), bridge().moveBox(box, x * 0.08, -1.0, z * 0.08));
    }

    @Override
    public void onTick() {
        if (sneakDelay > 0) {
            sneakDelay--;
        }
    }

    @Override
    protected void onStop() {
        sneakDelay = 0;
    }
}
