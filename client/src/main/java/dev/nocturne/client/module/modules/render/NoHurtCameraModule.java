package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** 通过真实 hurtCamera 门控关闭受伤镜头抖动。 */
public final class NoHurtCameraModule extends GameModule {
    public NoHurtCameraModule() {
        super("NoHurtCamera", Category.RENDER);
    }
}
