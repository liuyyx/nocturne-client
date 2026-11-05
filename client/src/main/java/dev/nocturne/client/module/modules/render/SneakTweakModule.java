package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Sneak Tweak：Default/Custom/Pre_1_14/Pre_1_9 潜行眼高与平滑速度。 */
public final class SneakTweakModule extends GameModule {
    private final BooleanValue smoothing = add(new BooleanValue("Smoothing", true));
    private final NumberValue speedPercentage = add(new NumberValue("Speed Percentage", 100, 25, 300, 5));
    private final ModeValue eyeMode = add(new ModeValue("Sneaking Eye Height", "Default", "Default", "Custom", "Pre_1_14", "Pre_1_9"));
    private final NumberValue customHeight = add(new NumberValue("Custom Sneaking Eye Height", 1.27, 0, 1.8, 0.01));
    private final BooleanValue thirdPerson = add(new BooleanValue("Third Person Eye Height", false));
    private float current = 1.62f;

    public SneakTweakModule() {
        super("Sneak Tweak", Category.RENDER);
    }

    @Override
    public void onTick() {
        float target = !state().shifting() ? 1.62f : eyeMode.is("Custom") ? customHeight.asFloat()
                : eyeMode.is("Pre_1_14") ? 1.54f : eyeMode.is("Pre_1_9") ? 1.62f : 1.27f;
        float speed = smoothing.get() ? Math.max(0.01f, speedPercentage.asFloat() / 100.0f) : 1.0f;
        current += (target - current) * speed;
        if (state().shifting() || thirdPerson.get()) {
            bridge().writeField(bridge().player(), ClassType.ENTITY, "eyeHeight", current);
        }
        bridge().callMappedStatus(bridge().player(), ClassType.ENTITY, "refreshDimensions");
    }

    @Override
    protected void onStop() {
        if (bridge() != null) {
            bridge().writeField(bridge().player(), ClassType.ENTITY, "eyeHeight", 1.62f);
            bridge().callMappedStatus(bridge().player(), ClassType.ENTITY, "refreshDimensions");
        }
        current = 1.62f;
    }
}
