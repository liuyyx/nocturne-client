package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/**
 * 图腾浮空物品姿势占位。
 *
 * <p>上游降级：原版浮空图腾由专用绘制姿势渲染，本仓库没有该绘制钩子，
 * {@code handTransform} 只作用于手持物品、不能复用。因此上游的 Mode / Speed 等
 * 姿势与速度设置全部删除——它们没有任何可观察效果，不应作为开关提供。
 * 本模块不注册、不声称功能等价，也无法取消原版浮空物品。
 */
public final class TotemAnimationModule extends GameModule {

    public TotemAnimationModule() { super("Totem Animation", Category.RENDER); }

    @Override protected void onStart() {
        System.out.println("[nocturne] Totem Animation: floating item render hook unavailable; module must not be registered");
    }
}
