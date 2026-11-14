package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/**
 * 帧后处理消费者读取启用状态，以整帧降采样及放大近似平滑边缘。
 *
 * <p>上游降级：这不是 FXAA，也不使用深度、运动向量或多重采样；画面细节可能一起变模糊。
 * 固定管线或不支持纹理/帧回读时无法生效。仅修改历史混合权重不能独立实现此效果。
 */
public final class AntiAliasModule extends GameModule {
    public AntiAliasModule() {
        super("Anti Alias", Category.RENDER);
    }
}
