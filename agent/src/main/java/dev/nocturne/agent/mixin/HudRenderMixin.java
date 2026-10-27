package dev.nocturne.agent.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Fabric 的游戏内 HUD 绘制入口。真正的指令由 {@link NocturneMixinPlugin#postApply} 插入。
 *
 * <p>与 Screen 的入口独立：世界内没有打开 Screen 时，仍必须由 HUD 驱动叠加层帧回调，
 * 否则 ESP 等 {@code WorldOverlay} 模块永远没有绘制机会。
 */
@Mixin(targets = "net.minecraft.class_329")
public class HudRenderMixin {
}
