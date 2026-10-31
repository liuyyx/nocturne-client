package dev.nocturne.client.runtime;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.ModuleRegistry;

/**
 * 帧分发器上的模块驱动。
 *
 * <p>1.8.9 的帧钩子直接调用 bootstrap 层的 {@link FrameDispatcher#dispatch()}（而不是
 * {@code NocturneRuntime.onFrame}），因此模块 tick 不能再挂在 {@code NocturneRuntime} 里；
 * 否则 GUI 能画、模块永远不执行（KillAura 开启却不攻击）。
 *
 * <p>节拍固定 20Hz，与世界身份变化同步激活闸门；异常由注册表逐模块隔离。
 */
public final class ModuleTicker implements FrameListener {

    private static final long TICK_INTERVAL_NANOS = 50_000_000L;

    private final NocturneClient client;
    private volatile long lastTickNanos;
    private Object lastWorld;
    private boolean worldBaselineInitialized;

    public ModuleTicker(NocturneClient client) {
        this.client = client;
    }

    @Override
    public void onFrame() {
        long now = System.nanoTime();
        if (lastTickNanos != 0L && now - lastTickNanos < TICK_INTERVAL_NANOS) {
            return;
        }
        lastTickNanos = now;
        GameBridge bridge = client.gameBridge();
        boolean inWorld = bridge != null && bridge.isResolved();
        ModuleRegistry registry = client.modules();
        if (inWorld != registry.isActive()) {
            registry.setActive(inWorld);
        }
        postWorldChange(bridge);
        client.events().post(dev.nocturne.client.event.TickEvent.INSTANCE);
        registry.tick();
    }

    /** 首次读取只建立基线；进入、离开与换图各投递一次。 */
    private void postWorldChange(GameBridge bridge) {
        if (bridge == null) {
            return;
        }
        Object minecraft = bridge.minecraft();
        if (minecraft == null) {
            return;
        }
        Object world = bridge.readField(minecraft,
                dev.nocturne.client.mapping.ClassType.MINECRAFT, "level");
        if (!worldBaselineInitialized) {
            lastWorld = world;
            worldBaselineInitialized = true;
            return;
        }
        if (world != lastWorld) {
            lastWorld = world;
            client.events().post(dev.nocturne.client.event.WorldChangeEvent.INSTANCE);
        }
    }
}
