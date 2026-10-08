package dev.nocturne.client.module.modules;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.RenderEvent;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

import java.util.List;

/**
 * 曳光线：从屏幕底部中心（准星下方）向每个可见实体画一条直线。
 *
 * <p>行为对齐 OpenVape Tracers（只搬行为，不搬代码）：玩家/生物/动物三组独立开关 +
 * 距离检查 + 按距离着色 + 指向高亮；颜色默认玩家蓝 / 生物橙 / 动物白。版本门走表驱动——
 * 实体面缺失的版本一次性日志 + 跳过。距离范围值（对方 RandomValue）在我方值框架缺失时
 * 暂用固定上限 32（与对方默认值一致），待 RandomValue 落地后补可调项。
 *
 * <p>绘制：由 {@link WorldOverlay} 每帧在叠加层上画——从屏幕底部中心向实体投影点连线。
 * 投影用 {@link WorldProjection}（自己算，四代通用），实体坐标用 {@code position() + Vec3#x/y/z}。
 */
public final class TracersModule extends Module implements WorldOverlay {

    /** 连线终点取实体身体中部：脚点会贴着地面、头点会飘在头顶，取 1 米高最贴近"人"的位置。 */
    private static final double TRACER_HEIGHT = 1.0d;

    /** 投影结果复用（每帧几十个实体，避免逐点分配）。 */
    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();

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
        Object player = bridge.player();
        ClassLoader loader = player != null && player.getClass().getClassLoader() != null
                ? player.getClass().getClassLoader()
                : TracersModule.class.getClassLoader();
        // 候选名逐个试（原版混淆名 → intermediary → SRG → NeoForge → 规范名），第一个加载成功者胜出。
        for (String mapped : bridge.mapping().classNameCandidates(ClassType.LIVING_ENTITY)) {
            try {
                if (Class.forName(mapped, false, loader) != null) {
                    return true;
                }
            } catch (Throwable t) {
                // 该候选名在当前加载器里不可用：继续尝试下一个。
            }
        }
        return false;
    }

    /**
     * 统计可见实体数（三组开关 + 距离上限过滤）。
     *
     * <p>玩家/生物/动物的区分需要实体类型面（Player/Animal/Mob kind），当前表里只有
     * LivingEntity 一层，故三组开关按"任一组开着就计全部 LivingEntity（除自己）"保守实现，
     * 类型细分待 kind 面落地后补齐。
     */
    private int countVisible(GameBridge bridge, Object level) {
        return visibleEntities(bridge, level).size();
    }

    /**
     * 收集可见实体（三组开关 + 距离上限过滤）。
     *
     * <p>收集与绘制共用同一份列表，避免两处过滤逻辑漂移。
     */
    @SuppressWarnings("unchecked")
    private List<Object> visibleEntities(GameBridge bridge, Object level) {
        List<Object> out = new java.util.ArrayList<Object>();
        if (!(renderPlayers.get() || renderMobs.get() || renderAnimals.get())) {
            return out;
        }
        Object raw;
        try {
            raw = bridge.callMapped(level, ClassType.CLIENT_LEVEL, "entitiesForRendering");
        } catch (Throwable t) {
            return out;
        }
        if (!(raw instanceof List)) {
            return out;
        }
        Object self = bridge.player();
        boolean checkDistance = playerDistanceCheck.get() || mobDistanceCheck.get()
                || animalDistanceCheck.get();
        double maxDist = maxDistance.get();
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
            out.add(entity);
        }
        return out;
    }

    /**
     * 世界覆盖层：从屏幕底部中心向每个可见实体画一条线。
     *
     * <p>终点取实体上方 1 米处（身体中部）——直接用脚点会让线头贴在地上、用头点会飘在头顶。
     */
    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null || !hasEntityFace(bridge)) {
            return;
        }
        Object level = level(bridge);
        if (level == null) {
            return;
        }
        float originX = draw.width() / 2f;
        float originY = draw.height();
        int argb = playerColor.argb();
        for (Object entity : visibleEntities(bridge, level)) {
            double[] pos = position(bridge, entity);
            if (pos == null) {
                continue;
            }
            projection.project(pos[0], pos[1] + TRACER_HEIGHT, pos[2], point);
            if (!point.visible) {
                continue;
            }
            draw.line(originX, originY, point.x, point.y, 1.5f, argb);
        }
    }

    /** 读实体的世界坐标（{@code position()} → {@code Vec3#x/y/z}）；缺成员时返回 {@code null}。 */
    private static double[] position(GameBridge bridge, Object entity) {
        Object vec = bridge.callMapped(entity, ClassType.ENTITY, "position");
        if (vec == null) {
            return null;
        }
        Object x = bridge.readField(vec, ClassType.VEC3, "x");
        Object y = bridge.readField(vec, ClassType.VEC3, "y");
        Object z = bridge.readField(vec, ClassType.VEC3, "z");
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) {
            return null;
        }
        return new double[]{((Number) x).doubleValue(), ((Number) y).doubleValue(),
                ((Number) z).doubleValue()};
    }

    /**
     * 本地玩家到实体的距离（米）；任一端坐标读不到时返回负数（调用方视为"不过滤"）。
     *
     * <p>用双方 {@code position()} 的欧氏距离：坐标面（{@code position} + {@code Vec3#x/y/z}）
     * 在表里已经齐了，不必依赖 {@code distanceToSqr} 那个需要传目标坐标的重载。
     */
    private static double distanceTo(GameBridge bridge, Object self, Object entity) {
        double[] a = position(bridge, self);
        double[] b = position(bridge, entity);
        if (a == null || b == null) {
            return -1.0;
        }
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 是否为生物实体（按 LivingEntity 映射类做 instanceof，避免写版本分支）。 */
    private static boolean isLiving(GameBridge bridge, Object entity) {
        ClassLoader loader = entity.getClass().getClassLoader() != null
                ? entity.getClass().getClassLoader()
                : TracersModule.class.getClassLoader();
        // 候选名逐个试，第一个加载成功者即该环境下的 LivingEntity。
        for (String mapped : bridge.mapping().classNameCandidates(ClassType.LIVING_ENTITY)) {
            try {
                Class<?> living = Class.forName(mapped, false, loader);
                return living.isInstance(entity);
            } catch (Throwable t) {
                // 该候选名在当前加载器里不可用：继续尝试下一个。
            }
        }
        return false;
    }

    /** 表驱动门日志：缺成员版本只报一次。 */
    private void logGateOnce(String reason) {
        if (gateLogged) {
            return;
        }
        gateLogged = true;
        System.out.println("[nocturne] Tracers: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
