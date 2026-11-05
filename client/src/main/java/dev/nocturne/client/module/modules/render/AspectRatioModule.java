package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/** Aspect Ratio：调整覆盖层投影使用的有效视场比例。 */
public final class AspectRatioModule extends GameModule {
    private final NumberValue ratio = add(new NumberValue("Ratio", 1.78, 0.1, 8.0, 0.1));

    public AspectRatioModule() {
        super("Aspect Ratio", Category.RENDER);
    }

    public float ratio() {
        return ratio.asFloat();
    }
}
