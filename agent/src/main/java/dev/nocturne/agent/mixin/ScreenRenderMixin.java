package dev.nocturne.agent.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * 绘制入口的 Mixin 空壳。
 *
 * <p>类体故意为空：真正的字节码改写全部在 {@link NocturneMixinPlugin#postApply} 里完成。
 *
 * <p>为什么要走 plugin 而不是 {@code @Inject}：{@code @Inject} 的注入方法必须精确声明目标
 * 方法的全部形参类型（{@code GuiGraphics}、{@code DeltaTracker} 等），而这些类型在不同
 * Minecraft 版本里名字完全不同（1.21.1 是 {@code class_332}、26.x 是
 * {@code GuiGraphicsExtractor}）。逐个版本写一份 mixin 类既不可维护，也让编译期必须绑定
 * 某一个具体版本的游戏 jar。{@code postApply} 直接拿到 ASM {@code ClassNode}，按运行时
 * 传入的「类名+方法名+描述符」精确命中，与版本无关。
 */
@Mixin(targets = "net.minecraft.class_437")
public class ScreenRenderMixin {
}
