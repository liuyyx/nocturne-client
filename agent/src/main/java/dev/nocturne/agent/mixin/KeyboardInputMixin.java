package dev.nocturne.agent.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * 键盘输入入口的 Mixin 空壳（仅 Fabric mod 形态需要）。
 *
 * <p>界面打开时游戏不应再收到按键与字符事件，否则 Esc/移动键会同时打到原生界面。
 * 真实改写见 {@link NocturneMixinPlugin#postApply}。
 */
@Mixin(targets = "net.minecraft.class_309")
public class KeyboardInputMixin {
}
