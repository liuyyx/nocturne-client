package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/** 由 GuiOverlay 的历史帧混合路径产生 MotionBlur。 */
public final class MotionBlurModule extends GameModule {
    private final NumberValue strength = add(new NumberValue("Strength", 0.65, 0.0, 0.95, 0.05));

    public MotionBlurModule() {
        super("MotionBlur", Category.RENDER);
    }

    public float strength() {
        return strength.asFloat();
    }
}
