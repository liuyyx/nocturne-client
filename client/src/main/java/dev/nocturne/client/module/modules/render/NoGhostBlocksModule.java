package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;

/**
 * Epsilon 上游 No Ghost Blocks 的破坏行为移植。
 *
 * <p>上游降级：仅实现 {@code Breaking}，不提供缺少放置注入点的 {@code Placing}。
 * 在真正的 {@code MultiPlayerGameMode#destroyBlock} 入口抑制客户端预测移除，
 * 保留开始/完成挖掘发包与松键重置，让方块等到服务端更新后消失。
 */
public final class NoGhostBlocksModule extends GameModule {
    private final BooleanValue breaking = add(new BooleanValue("Breaking", true));
    private boolean available;
    private boolean unavailableLogged;

    public NoGhostBlocksModule() {
        super("No Ghost Blocks", Category.RENDER);
    }

    @Override
    protected void onStart() {
        available = false;
        unavailableLogged = false;
    }

    @Override
    public void onTick() {
        // 配置装配与首帧 tick 都可能早于 agent 发布能力；接通前持续重探。
        if (available) return;
        available = dev.nocturne.client.bootstrap.GameplayHook.isWired("destroyBlock");
        if (available || unavailableLogged) return;
        unavailableLogged = true;
        System.out.println("[nocturne] No Ghost Blocks: local destroyBlock unavailable on this version (hook not wired)");
    }

    /** @return 是否等待服务端确认，而不立即移除客户端方块。 */
    public boolean shouldSuppressDestroy() {
        return available && breaking.get();
    }
}
