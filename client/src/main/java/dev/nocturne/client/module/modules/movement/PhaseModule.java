package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MotionEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Phase：Vanilla/Pearl/Sunrise/ForceMine/CCClip 五种穿墙相位策略。 */
public final class PhaseModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Vanilla", "Vanilla", "Pearl", "Sunrise", "ForceMine", "CCClip"));
    private final ModeValue swapMode = add(new ModeValue("Swap Mode", "InvSwitch", "Silent", "InvSwitch"));
    private final BooleanValue pauseOnPhase = add(new BooleanValue("Pause On Phase", true));
    private final BooleanValue swingHand = add(new BooleanValue("Swing Hand", true));
    private final BooleanValue silent = add(new BooleanValue("Silent", false));
    private final BooleanValue waitBreak = add(new BooleanValue("Wait Break", true));
    private final BooleanValue onlyOnGround = add(new BooleanValue("Only On Ground", true));
    private final BooleanValue autoDisable = add(new BooleanValue("Auto Disable", true));
    private final NumberValue breakTimeout = add(new NumberValue("Break Timeout", 4, 1, 20, 1));
    private final NumberValue pearlTimeout = add(new NumberValue("Pearl Timeout", 0, 0, 20, 1));
    private final NumberValue pitch = add(new NumberValue("Pitch", 80, 0, 90, 1));
    private final BooleanValue strict = add(new BooleanValue("Strict", true));
    private int ticks;
    private boolean physicsChanged;
    private boolean executed;

    public PhaseModule() {
        super("Phase", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MotionEvent.class, event -> {
            if (!event.pre || !mode.is("Vanilla") || pauseOnPhase.get() && executed) {
                return;
            }
            event.y -= 0.03;
            event.onGround = false;
        });
    }

    @Override
    public void onTick() {
        if (onlyOnGround.get() && !state().onGround() || pauseOnPhase.get() && executed) {
            return;
        }
        ticks++;
        double yaw = Math.toRadians(state().yaw());
        int x = (int) Math.floor(state().x() - Math.sin(yaw) * 0.6);
        int y = (int) Math.floor(state().y());
        int z = (int) Math.floor(state().z() + Math.cos(yaw) * 0.6);
        if (strict.get() && bridge().isAir(bridge().blockStateAt(x, y, z))) {
            return;
        }
        if (mode.is("Vanilla")) {
            physicsChanged = bridge().setNoPhysics(bridge().player(), true) || physicsChanged;
            actions().setMotion(-Math.sin(yaw) * 0.1, -0.03, Math.cos(yaw) * 0.1);
            return;
        }
        if (mode.is("Pearl")) {
            if (!executed) {
                int slot = EpsilonSupport.findHotbar(bridge(), "ender_pearl");
                if (slot < 0) {
                    setEnabled(false);
                    return;
                }
                int original = state().selectedSlot();
                actions().selectSlot(slot, silent.get() || swapMode.is("Silent"));
                client().rotations().request(state().yaw(), pitch.asFloat(), 8, 150_000_000L);
                actions().useItem();
                if (swingHand.get()) {
                    actions().swing(false, false);
                }
                actions().selectSlot(original, silent.get() || swapMode.is("Silent"));
                executed = true;
            }
            if (pearlTimeout.asInt() > 0 && ticks >= pearlTimeout.asInt()) {
                finish();
            }
            return;
        }
        if (mode.is("ForceMine")) {
            Object pos = bridge().blockPos(x, y, z);
            Object direction = bridge().staticField(ClassType.DIRECTION, "UP");
            EpsilonSupport.startDestroy(bridge(), pos, direction);
            if (!waitBreak.get() || ticks >= breakTimeout.asInt()) {
                actions().setMotion(-Math.sin(yaw) * 0.2, 0.0, Math.cos(yaw) * 0.2);
                finish();
            }
            return;
        }
        double distance = mode.is("CCClip") ? 0.35 : 0.1;
        actions().sendPositionRotation(state().x() - Math.sin(yaw) * distance,
                state().y() - (mode.is("Sunrise") ? 0.08 : 0.0), state().z() + Math.cos(yaw) * distance,
                state().yaw(), state().pitch(), false);
        finish();
    }

    private void finish() {
        executed = true;
        if (autoDisable.get()) {
            setEnabled(false);
        }
    }

    @Override
    protected void onStop() {
        if (physicsChanged && bridge() != null) {
            bridge().setNoPhysics(bridge().player(), false);
        }
        physicsChanged = false;
        ticks = 0;
        executed = false;
    }
}
