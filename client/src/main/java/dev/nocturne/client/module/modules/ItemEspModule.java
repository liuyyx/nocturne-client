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
 * <p>绘制：由 {@link WorldOverlay} 每帧在叠加层上画——掉落物上方一行标签（名字，可带距离）。
 * 收集改用 {@code ItemEntity} 精确判定：早先按"非生物实体"近似，箭/船/矿车都会被算成掉落物。
 */
public final class ItemEspModule extends Module implements WorldOverlay {

    /** 标签画在掉落物上方多高处（米）：掉落物本身很小，0.5 米就够，不会飘得太远。 */
    private static final double LABEL_HEIGHT = 0.5d;

    /** 投影结果复用（每帧几十个掉落物，避免逐点分配）。 */
    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();

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
        if (!hasItemFace(bridge)) {
            logGateOnce("掉落物面缺失（版本无该成员），ItemESP 已禁用");
            lastVisibleCount = 0;
            return;
        }
        lastVisibleCount = countVisible(bridge, level);
    }

    /** 取客户端世界；未进世界时返回 {@code null}。 */
    private static Object level(GameBridge bridge) {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
    }

    /** 掉落物面是否存在：{@code ItemEntity} 类可解析即认为可用。 */
    private static boolean hasItemFace(GameBridge bridge) {
        return resolveMapped(bridge, ClassType.ITEM_ENTITY, null) != null;
    }

    /**
     * 统计掉落物数。
     *
     * <p>白名单开且名单缺失时计 0（宁可不画不错画）——名单值类型还没落地。
     */
    private int countVisible(GameBridge bridge, Object level) {
        return visibleEntities(bridge, level).size();
    }

    /**
     * 收集掉落物（{@code ItemEntity} 实例）。
     *
     * <p>此前按"非 LivingEntity"近似，箭/船/矿车这些非生物实体也会被当成掉落物；现在按
     * {@code ItemEntity} 精确判定。收集与绘制共用同一份列表，避免两处过滤逻辑漂移。
     */
    @SuppressWarnings("unchecked")
    private List<Object> visibleEntities(GameBridge bridge, Object level) {
        List<Object> out = new java.util.ArrayList<Object>();
        if (whitelistOnly.get()) {
            // 名单值类型缺失：白名单开着但没有名单可匹配，保守不画。
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
        for (Object entity : (List<Object>) raw) {
            if (entity != null && isItemEntity(bridge, entity)) {
                out.add(entity);
            }
        }
        return out;
    }

    /**
     * 世界覆盖层：在每个掉落物上方画一行标签。
     *
     * <p>文本 = 物品名（{@code Entity#getName}），"Distance" 打开时追加米数。数量与分组需要物品堆
     * 面（{@code ItemStack#getCount}），尚未接——开关保留，不假装支持。
     */
    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null || !hasItemFace(bridge)) {
            return;
        }
        Object level = level(bridge);
        if (level == null) {
            return;
        }
        boolean withDistance = showDistance.get();
        float lineHeight = draw.textHeight();
        for (Object entity : visibleEntities(bridge, level)) {
            double[] pos = position(bridge, entity);
            if (pos == null) {
                continue;
            }
            projection.project(pos[0], pos[1] + LABEL_HEIGHT, pos[2], point);
            if (!point.visible) {
                continue;
            }
            String label = label(bridge, entity, withDistance);
            if (label == null) {
                continue;
            }
            draw.text(label, point.x - draw.textWidth(label) / 2f, point.y - lineHeight, 0xFFFFFFFF);
        }
    }

    /** 标签文本：物品名（+ 距离）；名字读不到时返回 {@code null}。 */
    private static String label(GameBridge bridge, Object entity, boolean withDistance) {
        Object name = bridge.callMapped(entity, ClassType.ENTITY, "getName");
        if (!(name instanceof String)) {
            return null;
        }
        if (!withDistance) {
            return (String) name;
        }
        double dist = distanceTo(bridge, bridge.player(), entity);
        return dist >= 0 ? name + " " + Math.round(dist) + "m" : (String) name;
    }

    /** 是否为掉落物（按 {@code ItemEntity} 映射类判定，不写版本分支）。 */
    private static boolean isItemEntity(GameBridge bridge, Object entity) {
        Class<?> type = resolveMapped(bridge, ClassType.ITEM_ENTITY, entity.getClass().getClassLoader());
        return type != null && type.isInstance(entity);
    }

    /**
     * 按映射候选名解析一个类；{@code loader} 为 {@code null} 时用本类的加载器。
     *
     * <p>候选顺序是「原版混淆名 → intermediary → SRG → NeoForge → 规范名」，第一个能加载的胜出——
     * 这样同一份代码在四种安装上都不需要版本分支。
     */
    private static Class<?> resolveMapped(GameBridge bridge, ClassType type, ClassLoader loader) {
        ClassLoader effective = loader != null ? loader : ItemEspModule.class.getClassLoader();
        for (String mapped : bridge.mapping().classNameCandidates(type)) {
            try {
                return Class.forName(mapped, false, effective);
            } catch (Throwable t) {
                // 该候选名在当前加载器里不可用：继续尝试下一个。
            }
        }
        return null;
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

    /** 本地玩家到实体的距离（米）；任一端坐标读不到时返回负数。 */
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
