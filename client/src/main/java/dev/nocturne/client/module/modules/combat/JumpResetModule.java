package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.Random;

/** 受到合法向上击退时按概率执行一次跳跃重置。 */
public final class JumpResetModule extends GameModule {
    private final NumberValue chance = add(new NumberValue("Chance", 40, 0, 100, 1));
    private final NumberValue accuracy = add(new NumberValue("Accuracy", 60, 0, 100, 1));
    private final BooleanValue waterCheck = add(new BooleanValue("Water check", false));
    private final Random random = new Random();
    private boolean pending;
    private boolean release;

    public JumpResetModule() {
        super("JumpReset", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, this::onPacket);
    }

    @Override
    protected void onStop() {
        pending = false;
        release = false;
    }

    private void onPacket(PacketEvent event) {
        if (event.direction != PacketEvent.Direction.RECEIVE || bridge() == null
                || !bridge().isLocalVelocity(event.packet)) {
            return;
        }
        int[] data = bridge().velocityData(event.packet);
        if (data != null && data[2] > 0 && random.nextDouble() * 100.0 < chance.get()
                && (!waterCheck.get() || !bridge().inWater(bridge().player()))) {
            pending = true;
        }
    }

    @Override
    public void onTick() {
        ActionApi actions = actions();
        if (actions == null) {
            return;
        }
        if (release) {
            actions.setJumping(false);
            release = false;
        }
        if (pending) {
            pending = false;
            if (random.nextDouble() * 100.0 < accuracy.get() && actions.setJumping(true)) {
                release = true;
            }
        }
    }
}
