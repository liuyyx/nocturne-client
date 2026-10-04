package dev.noturne.client.hud;

import java.util.function.Supplier;

/**
 * 客户端与 HUD 行渲染实现之间的接缝。
 *
 * <p>客户端模块不得依赖 UI 模块，因此模块通过这里发布文本行，由 UI 模块提供基于
 * {@code HudManager} 的实现。这样各模块只面向本接口编程。
 *
 * <p>线程契约：实现不要求自身线程安全——所有方法都由游戏侧单一线程调用（模块启停与
 * {@code setHudSink} 迁移发生在游戏/GUI 线程，帧回调同样在该线程），但实现必须假定
 * 调用时机不可预测：可能在帧绘制之间被调用，也可能在 {@code null} 汇被替换时被整体迁移。
 */
public interface HudSink {

    /**
     * 注册（或按 id 替换）一行 HUD 文本。
     *
     * @param id   行标识；重复注册同一 id 视为替换旧行
     * @param text 文本供给器，每帧被拉取一次，因此应返回实时值而非缓存值
     */
    void add(String id, Supplier<String> text);

    /** 移除指定 id 的行；该行不存在时无副作用。 */
    void remove(String id);

    /** @return 指定 id 的行当前是否已注册 */
    boolean has(String id);
}
