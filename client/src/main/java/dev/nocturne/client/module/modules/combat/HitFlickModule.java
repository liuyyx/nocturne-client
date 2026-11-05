package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.util.Random;

/** 攻击前瞬时 flick 朝向，随后恢复；可选在 flick 窗口扣包。 */
public final class HitFlickModule extends GameModule {
    private final NumberValue angle = add(new NumberValue("Angle", 90, 0, 360, 1));
    private final NumberValue chance = add(new NumberValue("Chance", 100, 0, 100, 1));
    private final NumberValue flickDelay = add(new NumberValue("Flick delay", 250, 0, 2000, 25));
    private final BooleanValue randomizeOffset = add(new BooleanValue("Randomize offset", false));
    private final NumberValue randomRange = add(new NumberValue("Random range", 0, 0, 180, 1));
    private final BooleanValue strafeInvert = add(new BooleanValue("Strafe invert", false));
    private final BooleanValue selectHits = add(new BooleanValue("Select hits", true));
    private final BooleanValue blink = add(new BooleanValue("Blink", false));
    private final BooleanValue limitItems = add(new BooleanValue("Limit to items", false));
    private final TextValue allowedItems = add(new TextValue("Allowed Items", "swords", 256));
    private final Random random = new Random();
    private long nextFlick;
    private int resetTicks;
    private boolean blinking;
    private float flickYaw;
    private float flickPitch;

    public HitFlickModule() {
        super("HitFlick", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, this::onAttack);
    }

    private void onAttack(AttackEvent event) {
        if (event.post || resetTicks > 0 || System.currentTimeMillis() < nextFlick
                || random.nextDouble() * 100.0 >= chance.get()
                || selectHits.get() && bridge().hurtTime(event.target) > 0) {
            return;
        }
        PlayerState state = state();
        if (limitItems.get() && !bridge().itemMatches(bridge().heldItem(state.player(), false), allowedItems.get())) {
            return;
        }
        float offset = angle.asFloat();
        if (randomizeOffset.get()) {
            offset += (float) ((random.nextDouble() - 0.5) * randomRange.get());
        }
        if (strafeInvert.get() && state.moving()) {
            offset = -offset;
        }
        ActionApi actions = actions();
        flickYaw = state.yaw();
        flickPitch = state.pitch();
        actions.sendRotation(RotationController.wrap(state.yaw() + offset), state.pitch(), state.onGround());
        if (blink.get()) {
            blinking = client().packets().hold(PacketEvent.Direction.SEND);
        }
        resetTicks = 2;
        nextFlick = System.currentTimeMillis() + flickDelay.asInt();
    }

    @Override
    public void onTick() {
        if (resetTicks > 0 && --resetTicks == 0) {
            PlayerState state = state();
            if (actions() != null) {
                actions().sendRotation(flickYaw, flickPitch, state.onGround());
            }
            release();
        }
    }

    @Override
    protected void onStop() {
        if (resetTicks > 0 && actions() != null) {
            actions().sendRotation(flickYaw, flickPitch, state().onGround());
        }
        release();
        resetTicks = 0;
    }

    private void release() {
        if (blinking) {
            client().packets().release(PacketEvent.Direction.SEND);
        }
        blinking = false;
    }
}
