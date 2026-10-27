package dev.nocturne.agent.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * 鼠标输入入口的 Mixin 空壳（仅 Fabric mod 形态需要）。
 *
 * <p>mod 形态没有 {@code Instrumentation}，所以 {@code InputBlockTransformer} 装不上；
 * 自定义界面开着时游戏仍会处理点击、并且每帧重新捕获指针，表现就是鼠标被拉回中心、
 * 点击同时打到后面的原生界面。真实改写见 {@link NocturneMixinPlugin#postApply}。
 */
@Mixin(targets = "net.minecraft.class_312")
public class MouseInputMixin {
}
