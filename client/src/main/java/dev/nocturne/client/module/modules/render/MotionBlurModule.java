package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon MotionBlur：强度、采样数、居中/向后算法、深度与刷新率选项。 */
public final class MotionBlurModule extends GameModule {
    private final NumberValue strength = add(new NumberValue("Strength", 1, 0, 5, 0.1));
    private final NumberValue samples = add(new NumberValue("Samples", 100, 4, 256, 4));
    private final ModeValue algorithm = add(new ModeValue("Algorithm", "Centered", "Backwards", "Centered"));
    private final BooleanValue depthBlur = add(new BooleanValue("Depth Blur", true));
    private final BooleanValue thirdPerson = add(new BooleanValue("Third Person", true));
    private final BooleanValue refreshRateScaling = add(new BooleanValue("Refresh Rate Scaling", true));

    public MotionBlurModule() {
        super("Motion Blur", Category.RENDER);
    }

    public float strength() {
        return Math.min(0.95f, strength.asFloat() * 0.19f);
    }

    public int samples() {
        return samples.asInt();
    }

    public boolean centered() {
        return algorithm.is("Centered");
    }

    public boolean depthBlur() {
        return depthBlur.get();
    }

    public boolean thirdPerson() {
        return thirdPerson.get();
    }

    public boolean refreshRateScaling() {
        return refreshRateScaling.get();
    }
}
