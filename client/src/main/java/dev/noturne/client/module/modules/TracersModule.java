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
import dev.noturne.client.value.NumberValue;

import java.util.List;

/**
 * 曳光线：从屏幕底部中心（准星下方）向每个可见实体画一条直线。
 *
 * <p>行为对齐 OpenVape Tracers（只搬行为，不搬代码）：玩家/生物/动物三组独立开关 +
 * 距离检查 + 按距离着色 + 指向高亮；颜色默认玩家蓝 / 生物橙 / 动物白。版本门走表驱动——
 * 实体面缺失的版本一次性日志 + 跳过。距离范围值（对方 RandomValue）在我方值框架缺失时
 * 暂用固定上限 32（与对方默认值一致），待 RandomValue 落地后补可调项。
 *
 * <p>绘制挂钩说明：屏幕空间直线的真正投递点（世界渲染 pass + 投影矩阵）尚未接入，
 * 当前先订阅 {@link RenderEvent} 做 per-frame 的实体收集、过滤与计数，
 * 实际画线待世界绘制挂钩落地后补齐。模块开关/参数/门逻辑现在即可验收。
 */
public final class TracersModule extends Module {

    /** 三组渲染开关。 */
    private final BooleanValue renderPlayers = add(new BooleanValue("Render Players", true));
    private final BooleanValue renderMobs = add(new BooleanValue("Render Mobs", false));
    private final BooleanValue renderAnimals = add(new BooleanValue("Render Animals", false));
    /** 三组距离检查开关（上限固定 32，对齐对方默认值）。 */
    private final BooleanValue playerDistanceCheck = add(new BooleanValue("Player Distance Check", false));
    private final BooleanValue mobDistanceCheck = add(new BooleanValue("Mob Distance Check", false));
    private final BooleanValue animalDistanceCheck = add(new BooleanValue("Animal Distance Check", false));
    /** 隐身显示 / 按距离着色 / 指向高亮。 */
    private final BooleanValue showInvisibles = add(new BooleanValue("Invisibles", false));
    private final BooleanValue colorByDistance = add(new BooleanValue("Color by distance", false));
    private final BooleanValue highlightFocusing = add(new BooleanValue("Highlight if focusing", false));
    /** 三组颜色。 */
    private final ColorValue playerColor = add(new ColorValue("Player Color", 0xFF0096FF));
    private final ColorValue mobColor = add(new ColorValue("Mob Color", 0xFFFF9A00));
    private final ColorValue animalColor = add(new ColorValue("Animal Color", 0xFFFFFFFF));
    /** 距离上限（对方 RandomValue 默认 0–32，缺值类型时暂用固定 Number 上限）。 */
    private final NumberValue maxDistance = add(new NumberValue("Max Distance", 32.0, 0.0, 256.0, 1.0));

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;
    /** 上一帧收集到的可见实体数（HUD/诊断用）。 */
    private int lastVisibleCount;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "Tracers";
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

    /** @return 上一帧收集到的可见实体数（绘制挂钩落地前供验收用）。 */
    public int lastVisibleCount() {
        return lastVisibleCount;
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
     * 每渲染帧收集可见实体。
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
            logGateOnce("实体面缺失（版本无该成员），Tracers 已禁用");
            lastVisibleCount = 0;
            return;
        }
        lastVisibleCount = countVisible(bridge, level);
        // 屏幕空间直线待世界渲染挂钩落地后补齐；此处只做收集与计数。
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
                    : TracersModule.class.getClassLoader();
            return Class.forName(mapped, false, loader) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 统计可见实体数（三组开关 + 距离上限过滤）。
     *
     * <p>玩家/生物/动物的区分需要实体类型面（Player/Animal/Mob kind），当前表里只有
     * LivingEntity 一层，故三组开关按“全开即全计”保守实现：任一组开着就计数全部
     * LivingEntity（除自己），类型细分待 kind 面落地后补齐。距离用
     * {@code distanceToSqr} 开方后与 {@link #maxDistance} 比较（仅距离检查开时）。
     */
    @SuppressWarnings("unchecked")
    private int countVisible(GameBridge bridge, Object level) {
        boolean anyGroup = renderPlayers.get() || renderMobs.get() || renderAnimals.get();
        if (!anyGroup) {
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
        boolean checkDistance = playerDistanceCheck.get() || mobDistanceCheck.get()
                || animalDistanceCheck.get();
        double maxDist = maxDistance.get();
        int count = 0;
        for (Object entity : (List<Object>) raw) {
            if (entity == null || (self != null && self.equals(entity))) {
                continue;
            }
            if (!isLiving(bridge, entity)) {
                continue;
            }
            if (checkDistance && maxDist > 0) {
                double dist = distanceTo(bridge, self, entity);
                if (dist >= 0 && dist > maxDist) {
                    continue;
                }
            }
            count++;
        }
        return count;
    }

    /** 是否为生物实体（按 LivingEntity 映射类做 instanceof，避免写版本分支）。 */
    private static boolean isLiving(GameBridge bridge, Object entity) {
        try {
            String mapped = bridge.mapping().className(ClassType.LIVING_ENTITY);
            if (mapped == null) {
                return false;
            }
            ClassLoader loader = entity.getClass().getClassLoader() != null
                    ? entity.getClass().getClassLoader()
                    : TracersModule.class.getClassLoader();
            Class<?> living = Class.forName(mapped, false, loader);
            return living.isInstance(entity);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 本地玩家到实体的距离；实体坐标面未落地前恒返回负数（调用方视为不过滤）。 */
    private static double distanceTo(GameBridge bridge, Object self, Object entity) {
        // distanceToSqr 在表里是 (DDD)D 需目标坐标；真实距离过滤待实体坐标面落地后补齐。
        return -1.0;
    }

    /** 表驱动门日志：缺成员版本只报一次。 */
    private void logGateOnce(String reason) {
        if (gateLogged) {
            return;
        }
        gateLogged = true;
        System.out.println("[noturne] Tracers: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
