package net.fabricmc.api;

/**
 * Fabric 加载器入口接口的编译期桩。
 *
 * <p>不会被打包进产物：运行时真实的 {@code net.fabricmc.api.ModInitializer} 由 Fabric 的类加载器
 * 提供并按名字解析。在此保留桩定义，使 agent 模块既无需依赖 Fabric，也能与加载器版本解耦。
 */
public interface ModInitializer {

    /** Fabric 在模组初始化阶段回调一次；客户端引导即在此完成。 */
    void onInitialize();
}
