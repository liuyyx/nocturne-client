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

import java.util.List;

/**
 * 名称标签：给其他玩家/生物/动物头顶画穿墙血条 + 名字 + 距离 + 装备/药水图标。
 *
 * <p>行为对齐 OpenVape NameTags（只搬行为，不搬代码）：三组独立开关（玩家/动物/怪物，
 * 各带血量/距离/药水/最大距离）+ 全局隐身忽略/自动缩放/缩放/Bot 隐藏 + 玩家组专属
 * 装备显示/强度指示/药水折算。对方未进 addValue 的 Opacity（透明度参与计算但无设置项）
 * 在我方按隐藏行为处理，不设可见项。版本门走表驱动——实体面缺失的版本一次性日志 + 跳过。
 *
 * <p>绘制挂钩说明：billboard 标签 + 装备/药水图标 + 伤害估算的真正投递点
 * （世界渲染 pass + 投影 + 字体图标管线）尚未接入，当前先订阅 {@link RenderEvent}
 * 做 per-frame 的实体收集、过滤与计数，实际画标签待世界绘制挂钩落地后补齐。
 * 模块开关/参数/门逻辑现在即可验收。
 */
public final class NameTagsModule extends Module {

    /** 隐身实体是否跳过。 */
    private final BooleanValue ignoreInvisibles = add(new BooleanValue("Ignore Invisibles", false));
    /** 按距离自动缩放标签。 */
    private final BooleanValue autoScale = add(new BooleanValue("Auto Scale", true));
    /** 全局缩放。 */
    private final NumberValue scale = add(new NumberValue("Scale", 1.0, 0.1, 1.5, 0.1));
    /** 隐藏 Bot。 */
    private final BooleanValue hideBots = add(new BooleanValue("Hide bots", true));
    /** 玩家组总开关 + 血量/距离/装备/药水/最大距离。 */
    private final BooleanValue renderPlayers = add(new BooleanValue("Render Players", true));
    private final BooleanValue playersHealth = add(new BooleanValue("Health", false));
    private final BooleanValue playersDistance = add(new BooleanValue("Distance", false));
    private final BooleanValue equipment = add(new BooleanValue("Equipment", false));
    private final BooleanValue playersEffects = add(new BooleanValue("Effects", false));
    private final NumberValue playersMaxDistance =
            add(new NumberValue("Player Max Distance", 0.0, 0.0, 250.0, 1.0));
    /** 玩家组专属：强度指示 + 药水折算。 */
    private final BooleanValue strengthIndicator = add(new BooleanValue("Strength Indicator", false));
    private final BooleanValue calculateEffects = add(new BooleanValue("Calculate Effects", false));
    /** 动物组总开关 + 血量/距离/药水/最大距离。 */
    private final BooleanValue renderAnimals = add(new BooleanValue("Render Animals", false));
    private final BooleanValue animalsHealth = add(new BooleanValue("Health", false));
    private final BooleanValue animalsDistance = add(new BooleanValue("Distance", false));
    private final BooleanValue animalsEffects = add(new BooleanValue("Effects", false));
    private final NumberValue animalsMaxDistance =
            add(new NumberValue("Animal Max Distance", 0.0, 0.0, 250.0, 1.0));
    /** 怪物组总开关 + 血量/距离/药水/最大距离。 */
    private final BooleanValue renderMobs = add(new BooleanValue("Render Mobs", false));
    private final BooleanValue mobsHealth = add(new BooleanValue("Health", false));
    private final BooleanValue mobsDistance = add(new BooleanValue("Distance", false));
    private final BooleanValue mobsEffects = add(new BooleanValue("Effects", false));
    private final NumberValue mobsMaxDistance =
            add(new NumberValue("Mob Max Distance", 0.0, 0.0, 250.0, 1.0));

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;
    /** 上一帧收集到的可见实体数（HUD/诊断用）。 */
    private int lastVisibleCount;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "NameTags";
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
            logGateOnce("实体面缺失（版本无该成员），NameTags 已禁用");
            lastVisibleCount = 0;
            return;
        }
        lastVisibleCount = countVisible(bridge, level);
        // billboard 标签 + 装备/药水图标待世界渲染挂钩落地后补齐；此处只做收集与计数。
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
                    : NameTagsModule.class.getClassLoader();
            return Class.forName(mapped, false, loader) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 统计可见实体数（三组开关过滤）。
     *
     * <p>玩家/动物/怪物的区分需要实体类型面，当前表里只有 LivingEntity 一层，
     * 故按“任一组开着就计数全部 LivingEntity（除自己）”保守实现，
     * 类型细分与血量/距离/装备/药水明细待实体上下文落地后补齐。
     */
    @SuppressWarnings("unchecked")
    private int countVisible(GameBridge bridge, Object level) {
        boolean anyGroup = renderPlayers.get() || renderAnimals.get() || renderMobs.get();
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
        int count = 0;
        for (Object entity : (List<Object>) raw) {
            if (entity == null || (self != null && self.equals(entity))) {
                continue;
            }
            if (!isLiving(bridge, entity)) {
                continue;
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
                    : NameTagsModule.class.getClassLoader();
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
        System.out.println("[noturne] NameTags: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
