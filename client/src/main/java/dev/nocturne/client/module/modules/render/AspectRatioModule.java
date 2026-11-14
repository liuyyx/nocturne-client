package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/**
 * Epsilon 上游 Aspect Ratio 的覆盖层降级移植。
 *
 * <p>上游降级：本仓库没有游戏投影矩阵钩子，无法改变真实游戏画面的宽高比。
 * 本模块只覆写自绘 2D 覆盖层（ESP 框 / 名牌 / 弹道等）的横向投影比例，
 * 通过 {@code WorldProjection#setAspectOverride} 生效；游戏世界、原版 HUD、
 * 第三人称模型、原版准星一律不受影响。关闭时恢复视口实际宽高比。
 */
public final class AspectRatioModule extends GameModule {
    private final NumberValue ratio = add(new NumberValue("Ratio", 1.78, 0.1, 8.0, 0.1));

    public AspectRatioModule() {
        super("Aspect Ratio", Category.RENDER);
    }

    /** @return 用户设定的覆盖层宽高比 */
    public float ratio() {
        return ratio.asFloat();
    }
}
