package dev.noturne.agent.mod;

import dev.noturne.client.NoturneClient;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge 加载器入口。由 NeoForge 扫描模组时通过 {@code @Mod} 注解发现并实例化。
 *
 * <p>与 Java agent 路径的区别：此处没有 {@link java.lang.instrument.Instrumentation} 句柄，
 * 因此只能引导客户端本体，无法安装帧钩子；逐帧渲染回调需由加载器侧另行提供。
 */
@Mod("noturne")
public final class NoturneNeoForge {

    /**
     * 构造即启动：由 NeoForge 在模组加载阶段调用一次。
     *
     * <p>以 {@code null} 作为插桩句柄，表示「非 agent 启动路径」。
     */
    public NoturneNeoForge() {
        NoturneClient.boot(null);
        // 同 Fabric：模组路径无 Instrumentation，只能挂加载器事件来驱动每帧逻辑。
        ModFrameDriver.install(NoturneNeoForge.class.getClassLoader());
    }
}
