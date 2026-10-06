package dev.nocturne.client.module.modules;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.RenderEvent;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.List;

/**
 * 掉落物透视：给世界里的掉落物品画标签（名 + 数量 + 可选距离）。
 *
 * <p>行为对齐 OpenVape ItemESP（只搬行为，不搬代码）：显示距离、近距物品分组、
 * 按距离自动缩放、缩放系数、白名单过滤。对方的名单值（LimitValue Allowed Items）
 * 在我方值框架缺失，暂按“白名单关 = 不过滤、白名单开 + 名单缺失 = 不画”保守实现，
 * 待名单值类型落地后补可调名单。版本门走表驱动——实体面缺失的版本一次性日志 + 跳过。
 *
 * <p>绘制挂钩说明：billboard 文字标签的真正投递点（世界渲染 pass + 投影）尚未接入，
 * 当前先订阅 {@link RenderEvent} 做 per-frame 的掉落物收集与计数，
 * 实际画标签待世界绘制挂钩落地后补齐。模块开关/参数/门逻辑现在即可验收。
 */
public final class ItemEspModule extends Module {

    /** 显示距离。 */
    private final BooleanValue showDistance = add(new BooleanValue("Distance", false));
    /** 近距物品分组合并。 */
    private final BooleanValue groupItems = add(new BooleanValue("Group Items", false));
    /** 按距离自动缩放标签。 */
    private final BooleanValue autoScale = add(new BooleanValue("Auto Scale", true));
    /** 标签缩放系数。 */
    private final NumberValue scale = add(new NumberValue("Scale", 1.0, 0.1, 1.5, 0.1));
    /** 仅渲染白名单物品（名单值缺失时保守实现，见类注释）。 */
    private final BooleanValue whitelistOnly = add(new BooleanValue("Whitelist Only", false));

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;
    /** 上一帧收集到的掉落物组数（HUD/诊断用）。 */
    private int lastVisibleCount;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "ItemESP";
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
        lastVisibleCount = 0;
        subscribe();
    }

    /** 禁用时退订渲染事件。 */
    @Override
    protected void onDisable() {
        unsubscribe();
        lastVisibleCount = 0;
    }

    /** 每 tick 确保订阅存在（总线晚于模块启用才就绪时补上）。 */
    @Override
    public void onTick() {
        if (renderSubscription == null) {
            subscribe();
        }
    }

    /** @return 上一帧收集到的掉落物组数（绘制挂钩落地前供验收用）。 */
    public int lastVisibleCount() {
        return lastVisibleCount;
    }

    /** 订阅渲染事件；总线未就绪时返回不抛，由 onTick 重试。 */
    private void subscribe() {
        NocturneClient client = NocturneClient.get();
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
            NocturneClient client = NocturneClient.get();
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
     * 每渲染帧收集掉落物。
     *
     * <p>表驱动门：实体面缺失的版本一次性日志 + 跳过，不刷屏不抛异常。
     */
    private void onRender(RenderEvent event) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object level = level(bridge);
        if (level == null) {
            lastVisibleCount = 0;
            return;
        }
        if (!hasEntityFace(bridge)) {
            logGateOnce("实体面缺失（版本无该成员），ItemESP 已禁用");
            lastVisibleCount = 0;
            return;
        }
        lastVisibleCount = countVisible(bridge, level);
        // billboard 标签待世界渲染挂钩落地后补齐；此处只做收集与计数。
    }

    /** 取客户端世界；未进世界时返回 {@code null}。 */
    private static Object level(GameBridge bridge) {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
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
                    : ItemEspModule.class.getClassLoader();
            return Class.forName(mapped, false, loader) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 统计掉落物组数。
     *
     * <p>掉落物判定的实体类型面（EntityItem kind）当前缺失，故按保守策略：
     * 白名单关闭时计数全部非玩家 LivingEntity 之外的实体（实现上退化为实体列表长度
     * 减玩家数，掉落物细分待 kind 面落地后补齐）；白名单开启时因名单值缺失暂计 0
     * （宁可不画不错画）。分组（groupItems 开时近距合并）同样待 kind 面落地。
     */
    @SuppressWarnings("unchecked")
    private int countVisible(GameBridge bridge, Object level) {
        if (whitelistOnly.get()) {
            // 名单值类型缺失：白名单开着但没有名单可匹配，保守不画。
            return 0;
        }
        Object raw;
        try {
            raw = bridge.callMapped(level, ClassType.CLIENT_LEVEL, "entitiesForRendering");
        } catch (Throwable t) {
            return 0;
        }
        if (!(raw instanceof List)) {
            return 0;
        }
        Object self = bridge.player();
        int count = 0;
        for (Object entity : (List<Object>) raw) {
            if (entity == null || (self != null && self.equals(entity))) {
                continue;
            }
            if (isLiving(bridge, entity)) {
                continue;
            }
            count++;
        }
        return count;
    }

    /** 是否为生物实体（掉落物不是生物实体；按 LivingEntity 映射类判定）。 */
    private static boolean isLiving(GameBridge bridge, Object entity) {
        try {
            String mapped = bridge.mapping().className(ClassType.LIVING_ENTITY);
            if (mapped == null) {
                return false;
            }
            ClassLoader loader = entity.getClass().getClassLoader() != null
                    ? entity.getClass().getClassLoader()
                    : ItemEspModule.class.getClassLoader();
            Class<?> living = Class.forName(mapped, false, loader);
            return living.isInstance(entity);
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
        System.out.println("[nocturne] ItemESP: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
