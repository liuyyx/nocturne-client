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
        client.packets().noteMainThread();
        dev.nocturne.client.event.GameplayEvents events = client.gameplayEvents();
        if (events != null) {
            // RECEIVE 事件在 Netty 线程只入队；这里在主线程投递，订阅者不会跨线程改模块状态。
            events.drainReceive();
        }
        GameBridge bridge = client.gameBridge();
        // 世界身份每帧检查：reset 必须早于本帧的 WorldChangeEvent 与模块 tick，缩小旧包窗口。
        postWorldChange(bridge);
        // 异线程 release / 延迟放行在这里真正出队。
        client.packets().pump();

        long now = System.nanoTime();
        if (lastTickNanos != 0L && now - lastTickNanos < TICK_INTERVAL_NANOS) {
            return;
        }
        lastTickNanos = now;
        boolean inWorld = worldReady(bridge);
        ModuleRegistry registry = client.modules();
        if (inWorld != registry.isActive()) {
            registry.setActive(inWorld);
        }
        client.events().post(dev.nocturne.client.event.TickEvent.INSTANCE);
        registry.tick();
    }

    /**
     * 世界是否真的就绪。
     *
     * <p>"类已解析"不等于"在世界里"：主菜单/死亡界面类照样解析成功，但 player/level 为空。
     * 旧判定让 {@code setActive} 永远为真，模块的 onSuspend/onResume 从不触发。
     *
     * <p>死亡界面也必须有独立闸门：player/level 在死亡时都非空，但此时 Blink/FakeLag 等
     * 继续压包毫无意义且会拖到重生后。血量可读且 ≤0 视为未就绪；血量读不到时保持"未知即
     * 存活"（非 LivingEntity 或映射缺失不能当成已死）。AutoRespawn 通过
     * {@link dev.nocturne.client.module.Module#runsWhileInactive()} 豁免本闸门。
     */
    static boolean worldReady(GameBridge bridge) {
        if (bridge == null) {
            return false;
        }
        Object player = bridge.player();
        if (player == null || bridge.world() == null) {
            return false;
        }
        Float health = bridge.healthOrNull(player);
        return health == null || health.floatValue() > 0.0f;
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
            // 顺序契约：先复位包闸门/队列并推进 epoch，再投递事件。任何在事件里重新 hold 的
            // 模块（Blink）都不会被随后的 reset 静默清掉；在途 capture/drain 因 epoch 不匹配丢弃。
            client.packets().onWorldChange();
            client.events().post(dev.nocturne.client.event.WorldChangeEvent.INSTANCE);
        }
    }
}
