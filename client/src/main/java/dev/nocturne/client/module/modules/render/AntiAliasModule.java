package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** Anti Alias：由 FrameEffects 对真实帧执行边缘平滑。 */
public final class AntiAliasModule extends GameModule {
    public AntiAliasModule() {
        super("Anti Alias", Category.RENDER);
    }
}
