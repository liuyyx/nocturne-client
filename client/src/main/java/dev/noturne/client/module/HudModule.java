package dev.noturne.client.module;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.hud.HudSink;

import java.util.function.Supplier;

/**
 * 仅用于 HUD 显示的模块：启用时发布一行文字，禁用时移除该行。
 *
 * <p>它不参与 {@link Module#onTick()}：文本由 HUD 每帧拉取，因此本类只管理注册与注销。
 */
public abstract class HudModule extends Module {

    /** HUD 接收器使用的稳定 id，必须在各模块间唯一。 */
    protected abstract String hudId();

    /**
     * 每帧拉取一次；返回 {@code null} 或空串表示本帧不绘制任何内容。
     *
     * <p>供子类在字段初始化表达式中直接赋值；HUD 每次取值都会重新调用，因此不能缓存
     * 其结果。
     */
    protected abstract Supplier<String> hudText();

    /**
     * 启用时向 HUD 接收器注册本模块的文本行。
     *
     * <p>传入的是 {@link #hudText()} 的 supplier 实例而非字符串，HUD 侧按 {@link #hudId()}
     * 覆盖而非重复添加。
     */
    @Override
    protected void onEnable() {
        HudSink sink = sink();
        if (sink != null) {
            sink.add(hudId(), hudText());
        }
    }

    /** 禁用时向 HUD 接收器注销本模块的文本行；必须与 {@link #onEnable()} 使用同一 id。 */
    @Override
    protected void onDisable() {
        HudSink sink = sink();
        if (sink != null) {
            sink.remove(hudId());
        }
    }

    /**
     * 获取当前客户端实例的 HUD 接收器；客户端尚未构造时返回 {@code null}。
     *
     * <p>客户端可能在模块启用之后才构造完成，故每次都重新获取而非缓存；{@code null}
     * 时调用方静默跳过注册/注销，避免早期崩溃。
     */
    private static HudSink sink() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.hudSink();
    }
}
