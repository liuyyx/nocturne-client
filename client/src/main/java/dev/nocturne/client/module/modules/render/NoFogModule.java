package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** 通过真实 GameRenderer.setupFog 门控禁用雾。 */
public final class NoFogModule extends GameModule {
    public NoFogModule() {
        super("NoFog", Category.RENDER);
    }
}
