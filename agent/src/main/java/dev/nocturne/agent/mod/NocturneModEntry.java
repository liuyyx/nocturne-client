package dev.nocturne.agent.mod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 模组入口。
 *
 * <p><b>为什么需要它</b>：Mixin 只能在 {@code DEFAULT} 阶段之前注册配置，而
 * {@code Mixins.addConfiguration} 在游戏启动后调用时，该阶段的配置早已扫描完毕——注册成功、
 * 但不会有任何 mixin 被解析（1.21.1 实测：日志显示 registered，却没有 woven 输出）。
 * {@code fabric.mod.json} 的 {@code mixins} 字段是唯一能让 Fabric loader 在初始化阶段把我们的
 * 配置交给 Mixin 的入口。
 *
 * <p>本类在 mod 形态下触发客户端引导；{@code -javaagent} / attach 形态仍走
 * {@code NocturneAgent}。
 */
public final class NocturneModEntry implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        try {
            // 模组入口没有 agent 参数；版本必须从 Fabric 的 Minecraft 元数据取得，
            // 否则下游会选恒等映射，在 intermediary 环境里找不到游戏主类。
            String version = FabricLoader.getInstance().getModContainer("minecraft")
                    .orElseThrow(() -> new IllegalStateException("Minecraft mod metadata missing"))
                    .getMetadata().getVersion().getFriendlyString();
            Class<?> agent = Class.forName("dev.nocturne.agent.NocturneAgent");
            agent.getMethod("startAsMod", String.class).invoke(null, version);
        } catch (Throwable error) {
            System.out.println("[nocturne] mod entry failed: " + error);
        }
    }
}
