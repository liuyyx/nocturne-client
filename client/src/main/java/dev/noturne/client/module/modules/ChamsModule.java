package dev.noturne.client.module.modules;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.event.RenderEvent;
import dev.noturne.client.event.EventBus;
import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.value.BooleanValue;
import dev.noturne.client.value.ColorValue;

/**
 * 透视人体：让玩家模型无视深度穿墙显示（非着色走 polygon-offset，着色走双 pass 纯色）。
 *
 * <p>行为对齐 OpenVape Chams（只搬行为，不搬代码）：Bot 隐藏、着色开关、
 * 可见色/墙后色、墙后异色开关。对方挂在实体/玩家渲染前后 5 个事件点上
 * （取消原渲染 + 关深度重绘墙后色 + 再绘可见色 + 恢复状态），版本门为 1.16.5 之前
 * （ForgeVersion 门），走表驱动——实体渲染拦截面缺失的版本一次性日志 + 跳过。
 *
 * <p>绘制挂钩说明：实体渲染拦截（取消原渲染 + 双 pass 重绘）的真正投递点尚未接入，
 * 当前先订阅 {@link RenderEvent} 做 per-frame 的门检查，实际双 pass 重绘待实体渲染
 * 挂钩落地后补齐。模块开关/参数/门逻辑现在即可验收。
 */
public final class ChamsModule extends Module {

    /** Bot 不透视。 */
    private final BooleanValue hideBots = add(new BooleanValue("Hide Bots", false));
    /** 着色模式（纯色双 pass）；关 = polygon-offset 透视。 */
    private final BooleanValue colored = add(new BooleanValue("Colored", false));
    /** 可见色。 */
    private final ColorValue visibleColor = add(new ColorValue("Visible Color", 0xFFFF0000));
    /** 墙后异色开关。 */
    private final BooleanValue colorBehindWalls = add(new BooleanValue("Color Behind Walls", true));
    /** 墙后色。 */
    private final ColorValue occludedColor = add(new ColorValue("Invisible Color", 0xFFFFFF00));

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "Chams";
    }

    /** 归入“渲染”分组。 */
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

    /** 禁用时退订渲染事件（双 pass 状态由挂钩落地后的实现负责恢复，当前无副作用可撤）。 */
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
     * <p>表驱动门：实体渲染拦截面（RenderManager/实体渲染器）在当前表里没有独立面，
     * 以 LivingEntity 面存在性为准；缺失的版本一次性日志 + 跳过。
     * 双 pass 重绘（取消原渲染 → 关深度绘墙后色 → 绘可见色 → 恢复）待实体渲染挂钩
     * 落地后补齐；当前只做门检查，不碰任何游戏状态。
     */
    private void onRender(RenderEvent event) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        if (bridge.player() == null) {
            return;
        }
        if (!hasEntityFace(bridge)) {
            logGateOnce("实体面缺失（版本无该成员），Chams 已禁用");
            return;
        }
        // 双 pass 重绘待实体渲染挂钩落地后补齐；此处只做门检查。
    }

    /** 实体面是否存在：LivingEntity 类可解析即认为可用。 */
    private static boolean hasEntityFace(GameBridge bridge) {
        try {
            String mapped = bridge.mapping().className(ClassType.LIVING_ENTITY);
            if (mapped == null) {
                return false;
            }
            Object player = bridge.player();
            ClassLoader loader = player != null && player.getClass().getClassLoader() != null
                    ? player.getClass().getClassLoader()
                    : ChamsModule.class.getClassLoader();
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
        System.out.println("[noturne] Chams: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
