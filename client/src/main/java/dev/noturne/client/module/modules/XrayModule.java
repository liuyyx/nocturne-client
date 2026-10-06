package dev.noturne.client.module.modules;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.event.RenderEvent;
import dev.noturne.client.event.EventBus;
import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.value.BooleanValue;
import dev.noturne.client.value.NumberValue;

/**
 * 矿石透视：只渲染白名单方块（金/铁/钻石/绿宝石/青金石矿 + 金/铁/钻石/绿宝石块），其余全隐。
 *
 * <p>行为对齐 OpenVape XRay（只搬行为，不搬代码）：透明度、洞穴模式（仅暴露于空气的
 * 矿石）、白名单。对方注册门为 1.16.5 之前（versionConstrainedModules +
 * addMinecraft1165Constraint），走表驱动——区块/方块管线面缺失的版本一次性日志 +
 * 跳过。对方的名单值（OptionalLimitValue Xray Blocks）在我方值框架缺失，
 * 暂按默认 9 种白名单行为实现（名单不可调），待名单值类型落地后补可调名单。
 *
 * <p>绘制挂钩说明：区块重建/方块面/模型/流体渲染拦截的真正投递点尚未接入，
 * 当前先订阅 {@link RenderEvent} 做 per-frame 的门检查，
 * 实际透视重载待区块/方块管线挂钩落地后补齐。模块开关/参数/门逻辑现在即可验收。
 *
 * <p>分类说明：对方是 WORLD，本批按 B1 渲染显示类归入 RENDER（分类映射表落地后统一）。
 */
public final class XrayModule extends Module {

    /** 非目标方块的透明度（对方 1.7.10 隐藏本项，我方保留，缺成员版本走门逻辑）。 */
    private final NumberValue opacity = add(new NumberValue("Opacity", 60.0, 0.0, 255.0, 1.0));
    /** 仅显示暴露于空气的矿石。 */
    private final BooleanValue caveMode = add(new BooleanValue("Cave Mode", false));

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "Xray";
    }

    /** 归入“渲染”分组（B1 批内约定；分类映射表落地后统一）。 */
    @Override
    public Category category() {
        return Category.RENDER;
    }

    /** 启用时订阅渲染事件。 */
    @Override
    protected void onEnable() {
        gateLogged = false;
        subscribe();
    }

    /** 禁用时退订渲染事件（渲染器重载还原由挂钩落地后的实现负责，当前无副作用可撤）。 */
    @Override
    protected void onDisable() {
        unsubscribe();
    }

    /** 每 tick 确保订阅存在（总线晚于模块启用才就绪时补上）。 */
    @Override
    public void onTick() {
        if (renderSubscription == null) {
            subscribe();
        }
    }

    /** 订阅渲染事件；总线未就绪时返回不抛，由 onTick 重试。 */
    private void subscribe() {
        NoturneClient client = NoturneClient.get();
        if (client == null || renderSubscription != null) {
            return;
        }
        try {
            renderSubscription = client.events().subscribe(RenderEvent.class, this::onRender);
        } catch (Throwable t) {
            renderSubscription = null;
        }
    }

    /** 退订渲染事件。 */
    private void unsubscribe() {
        if (renderSubscription == null) {
            return;
        }
        try {
            NoturneClient client = NoturneClient.get();
            if (client != null) {
                client.events().unsubscribe(renderSubscription);
            }
        } catch (Throwable ignored) {
            // 退订失败不影响禁用语义。
        } finally {
            renderSubscription = null;
        }
    }

    /**
     * 每渲染帧做门检查。
     *
     * <p>表驱动门（含对方 1.16.5 之前注册门的翻译）：区块面
     * （LevelChunk#getBlockEntities）在当前表里是 StorageESP 已验证存在的面，
     * 以它存在性为准；缺失的版本一次性日志 + 跳过。区块重建拦截与渲染器重载
     * 待管线挂钩落地后补齐；当前只做门检查，不碰任何游戏状态。
     */
    private void onRender(RenderEvent event) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        if (bridge.player() == null) {
            return;
        }
        if (!hasChunkFace(bridge)) {
            logGateOnce("区块面缺失（版本无该成员），Xray 已禁用");
            return;
        }
        // 区块重建拦截 + 渲染器重载待管线挂钩落地后补齐；此处只做门检查。
    }

    /** 区块面是否存在：LevelChunk 类可解析即认为可用。 */
    private static boolean hasChunkFace(GameBridge bridge) {
        try {
            String mapped = bridge.mapping().className(ClassType.CLIENT_LEVEL);
            if (mapped == null) {
                return false;
            }
            Object player = bridge.player();
            ClassLoader loader = player != null && player.getClass().getClassLoader() != null
                    ? player.getClass().getClassLoader()
                    : XrayModule.class.getClassLoader();
            return Class.forName(mapped, false, loader) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 表驱动门日志：缺成员版本只报一次。 */
    private void logGateOnce(String reason) {
        if (gateLogged) {
            return;
        }
        gateLogged = true;
        System.out.println("[noturne] Xray: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
