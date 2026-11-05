package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** 由 GuiOverlay 的真实帧降采样路径处理容器背景模糊。 */
public final class InventoryBlurModule extends GameModule {
    public InventoryBlurModule() {
        super("InventoryBlur", Category.RENDER);
    }
}
