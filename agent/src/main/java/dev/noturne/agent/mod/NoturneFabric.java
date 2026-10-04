package dev.noturne.agent.mod;

import dev.noturne.client.NoturneClient;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric 加载器入口。由 {@code fabric.mod.json} 的 {@code entrypoints.main} 引用并回调。
 *
 * <p>{@link ModInitializer} 在运行时由 Fabric 的类加载器提供，本模块只需编译期可见即可，
 * 因此不必依赖任何 Fabric 构件。与 Java agent 路径一样，此处没有插桩句柄，只引导客户端本体。
 */
public final class NoturneFabric implements ModInitializer {

    /**
     * Fabric 初始化回调：加载器在模组初始化阶段调用一次。
     *
     * <p>以 {@code null} 作为插桩句柄，表示「非 agent 启动路径」。
     */
    @Override
    public void onInitialize() {
        NoturneClient.boot(null);
        // 模组路径没有 Instrumentation，帧钩子装不了；改挂加载器的逐帧渲染事件，
        // 否则客户端起来了却没有任何东西驱动它的每帧逻辑与叠加层。
        ModFrameDriver.install(NoturneFabric.class.getClassLoader());
    }
}
