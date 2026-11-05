package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.MotionEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** NoFall：GroundSpoof 与 GrimJump 两种落地伤害规避。 */
public final class NoFallModule extends GameModule {
    private static final double TRIGGER_DISTANCE = 2.224;
    private final ModeValue mode = add(new ModeValue("Mode", "GroundSpoof", "GroundSpoof", "GrimJump"));
    private final NumberValue fallDistance = add(new NumberValue("Fall Distance", 3.0, 3, 15, 0.1));
    private int grimTicks;

    public NoFallModule() {
        super("NoFall", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(MotionEvent.class, event -> {
            if (!event.pre || event.onGround) {
                return;
            }
            Object player = bridge().player();
            boolean gliding = Boolean.TRUE.equals(bridge().callMapped(player, ClassType.LIVING_ENTITY, "isFallFlying"));
            double[] velocity = bridge().entityVelocity(player);
            if (mode.is("GroundSpoof") && shouldOverride(gliding, false, false,
                    bridge().fallDistance(player), velocity[1])
                    && bridge().fallDistance(player) >= fallDistance.get()) {
                event.onGround = true;
            }
        });
    }

    @Override
    public void onTick() {
        if (!mode.is("GrimJump")) {
            return;
        }
        float distance = bridge().fallDistance(bridge().player());
        if (distance >= fallDistance.get() && bridge().entityVelocity(bridge().player())[1] < 0.0) {
            actions().sendGround(true);
            if (grimTicks++ % 2 == 0) {
                actions().setJumping(true);
            }
        } else {
            grimTicks = 0;
        }
    }

    static boolean shouldOverride(boolean fallFlying, boolean flying, boolean instabuild,
                                  double distance, double motionY) {
        return !fallFlying && !flying && !instabuild && distance > TRIGGER_DISTANCE && motionY < 0.0;
    }
}
