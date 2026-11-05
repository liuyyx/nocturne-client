package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Derp：Static/Offset/Random/Jitter/Spin 与 Static/Offset/Random 俯仰旋转。 */
public final class DerpModule extends GameModule {
    private final ModeValue yawMode = add(new ModeValue("Yaw", "Random", "Static", "Offset", "Random", "Jitter", "Spin"));
    private final ModeValue pitchMode = add(new ModeValue("Pitch", "Random", "Static", "Offset", "Random"));
    private final BooleanValue safePitch = add(new BooleanValue("Safe Pitch", true));
    private final BooleanValue notDuringSprint = add(new BooleanValue("Not During Sprint", true));
    private final NumberValue yawValue = add(new NumberValue("Yaw Value", 0, -180, 180, 1));
    private final NumberValue yawOffset = add(new NumberValue("Yaw Offset", 0, -180, 180, 1));
    private final NumberValue forwardTicks = add(new NumberValue("Forward Ticks", 2, 0, 100, 1));
    private final NumberValue backwardTicks = add(new NumberValue("Backward Ticks", 2, 0, 100, 1));
    private final NumberValue spinSpeed = add(new NumberValue("Spin Speed", 50, -70, 70, 1));
    private final NumberValue pitchValue = add(new NumberValue("Pitch Value", -90, -180, 180, 1));
    private final NumberValue pitchOffset = add(new NumberValue("Pitch Offset", 0, -180, 180, 1));
    private int ticks;
    private float spin;

    public DerpModule() {
        super("Derp", Category.RENDER);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, event -> {
            if (notDuringSprint.get() && event.sprint) {
                client().rotations().release(1);
            }
        });
    }

    @Override
    public void onTick() {
        if (notDuringSprint.get() && state().sprinting() || bridge().usingItem(bridge().player())) {
            return;
        }
        ticks++;
        float yaw;
        if (yawMode.is("Static")) yaw = yawValue.asFloat();
        else if (yawMode.is("Offset")) yaw = state().yaw() + yawOffset.asFloat();
        else if (yawMode.is("Jitter")) {
            int forward = forwardTicks.asInt();
            int backward = backwardTicks.asInt();
            yaw = state().yaw() + (ticks % Math.max(1, forward + backward) < forward ? 90.0f : -90.0f);
        } else if (yawMode.is("Spin")) {
            spin += spinSpeed.asFloat();
            yaw = spin;
        } else yaw = dev.nocturne.client.module.modules.EpsilonSupport.RANDOM.nextFloat() * 360.0f - 180.0f;
        float pitch;
        if (pitchMode.is("Static")) pitch = pitchValue.asFloat();
        else if (pitchMode.is("Offset")) pitch = state().pitch() + pitchOffset.asFloat();
        else pitch = dev.nocturne.client.module.modules.EpsilonSupport.RANDOM.nextFloat() * 180.0f - 90.0f;
        if (safePitch.get()) {
            pitch = Math.max(-90.0f, Math.min(90.0f, pitch));
        }
        client().rotations().request(yaw, pitch, 1, 150_000_000L);
    }

    @Override
    protected void onStop() {
        client().rotations().release(1);
        ticks = 0;
    }
}
