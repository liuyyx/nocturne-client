package dev.nocturne.client.module.modules.render;

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
 * 名称标签：给其他玩家/生物/动物头顶画穿墙血条 + 名字 + 距离 + 装备/药水图标。
 *
 * <p>行为对齐 OpenVape NameTags（只搬行为，不搬代码）：三组独立开关（玩家/动物/怪物，
 * 各带血量/距离/药水/最大距离）+ 全局隐身忽略/自动缩放/缩放/Bot 隐藏 + 玩家组专属
 * 装备显示/强度指示/药水折算。对方未进 addValue 的 Opacity（透明度参与计算但无设置项）
 * 在我方按隐藏行为处理，不设可见项。版本门走表驱动——实体面缺失的版本一次性日志 + 跳过。
 *
 * <p>绘制：由 {@link WorldOverlay} 每帧在叠加层上画——实体头顶上方一行标签（名字，可带距离）。
 * 图标（装备/药水）与血量条需要物品面与图标管线，尚未接；开关保留。
 */
public final class NameTagsModule extends Module implements WorldOverlay {

    /** 标签画在实体头顶上方多高处（米）。 */
    private static final double TAG_HEIGHT = 2.1d;

    /** 投影结果复用（每帧几十个实体，避免逐点分配）。 */
    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();

    /** 隐身实体是否跳过（读不到隐身状态时按"可见"处理）。 */
    private final BooleanValue ignoreInvisibles = add(new BooleanValue("Ignore Invisibles", false));
    /** 玩家组总开关 + 血量/距离/最大距离。 */
    private final BooleanValue renderPlayers = add(new BooleanValue("Render Players", true));
    private final BooleanValue playersHealth = add(new BooleanValue("Health", false));
    private final BooleanValue playersDistance = add(new BooleanValue("Distance", false));
    private final NumberValue playersMaxDistance =
            add(new NumberValue("Player Max Distance", 0.0, 0.0, 250.0, 1.0));
    /** 动物组总开关 + 血量/距离/最大距离。 */
    private final BooleanValue renderAnimals = add(new BooleanValue("Render Animals", false));
    private final BooleanValue animalsHealth = add(new BooleanValue("Health", false));
    private final BooleanValue animalsDistance = add(new BooleanValue("Distance", false));
    private final NumberValue animalsMaxDistance =
            add(new NumberValue("Animal Max Distance", 0.0, 0.0, 250.0, 1.0));
    /** 怪物组总开关 + 血量/距离/最大距离。 */
    private final BooleanValue renderMobs = add(new BooleanValue("Render Mobs", false));
    private final BooleanValue mobsHealth = add(new BooleanValue("Health", false));
    private final BooleanValue mobsDistance = add(new BooleanValue("Distance", false));
    private final NumberValue mobsMaxDistance =
            add(new NumberValue("Mob Max Distance", 0.0, 0.0, 250.0, 1.0));

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private volatile EventBus.Subscription renderSubscription;
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
        synchronized (this) {
            if (isEnabled() && renderSubscription == null) {
                // 只在启用状态下自愈重订阅：禁用后照旧重订阅会让监听器复活——GUI 关掉的模块
                // 会被游戏线程的 tick 又订回去，而且每次 subscribe 都新建 lambda，永远摘不干净。
                subscribe();
            }
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
        ClassLoader loader = bridge.gameLoader();
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
     * 统计可见实体数（三组开关过滤）。
     *
     * <p>玩家/动物/怪物的区分需要实体类型面，当前表里只有 LivingEntity 一层，
     * 故按“任一组开着就计数全部 LivingEntity（除自己）”保守实现，
     * 类型细分与血量/距离/装备/药水明细待实体上下文落地后补齐。
     */
    @SuppressWarnings("unchecked")
    private int countVisible(GameBridge bridge, Object level) {
        return visibleEntities(bridge, level).size();
    }

    /** 收集可见实体（三组开关，除自己）；收集与绘制共用同一份列表。 */
    @SuppressWarnings("unchecked")
    private List<Object> visibleEntities(GameBridge bridge, Object level) {
        List<Object> out = new java.util.ArrayList<Object>();
        if (!(renderPlayers.get() || renderAnimals.get() || renderMobs.get())) {
            return out;
        }
        Object raw;
        try {
            raw = bridge.callMapped(level, ClassType.CLIENT_LEVEL, "entitiesForRendering");
        } catch (Throwable t) {
            return out;
        }
        // 1.15+ 返回 Iterable（1.8.9–1.12.2 才是 List）：只认 List 会让中间版本静默零输出。
        if (!(raw instanceof Iterable)) {
            return out;
        }
        Object self = bridge.player();
        for (Object entity : (Iterable<Object>) raw) {
            if (entity == null || (self != null && self.equals(entity))) {
                continue;
            }
            if (!isLiving(bridge, entity)) {
                continue;
            }
            if (ignoreInvisibles.get() && isInvisible(bridge, entity)) {
                continue;
            }
            out.add(entity);
        }
        return out;
    }

    /**
     * 世界覆盖层：在每个可见实体头顶画一行标签。
     *
     * <p>文本 = 名字（{@code Entity#getName}），"Distance" 打开时追加米数。血量条与装备/药水图标
     * 需要物品面与图标管线，尚未接——开关保留，不假装支持。
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
        boolean withDistance = playersDistance.get() || animalsDistance.get() || mobsDistance.get();
        boolean withHealth = playersHealth.get() || animalsHealth.get() || mobsHealth.get();
        float lineHeight = draw.textHeight();
        for (Object entity : visibleEntities(bridge, level)) {
            double[] pos = position(bridge, entity);
            if (pos == null) {
                continue;
            }
            projection.project(pos[0], pos[1] + TAG_HEIGHT, pos[2], point);
            if (!point.visible) {
                continue;
            }
            String label = label(bridge, entity, withDistance, withHealth);
            if (label == null) {
                continue;
            }
            draw.text(label, point.x - draw.textWidth(label) / 2f, point.y - lineHeight, 0xFFFFFFFF);
        }
    }

    /**
     * 标签文本：名字 [+ 血量] [+ 距离]；名字读不到时返回 {@code null}。
     *
     * <p>血量与距离的开关按"任一分组打开即生效"处理：按实体类型细分需要 Animal 等映射面
     * （现在只有 Player/Mob 两级），分组开关暂时合流——比"开关完全没人看"好得多，
     * 等 kind 面进表后再按组精确匹配。
     */
    private static String label(GameBridge bridge, Object entity, boolean withDistance,
                                boolean withHealth) {
        // getName 返回的是聊天组件而不是字符串，必须经映射表读文本（见 GameBridge#componentText）。
        String name = bridge.componentText(bridge.callMapped(entity, ClassType.ENTITY, "getName"));
        if (name == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(name);
        if (withHealth) {
            Object health = bridge.callMapped(entity, ClassType.LIVING_ENTITY, "getHealth");
            if (health instanceof Number) {
                text.append(' ').append(Math.round(((Number) health).doubleValue())).append("hp");
            }
        }
        if (withDistance) {
            double dist = distanceTo(bridge, bridge.player(), entity);
            if (dist >= 0) {
                text.append(' ').append(Math.round(dist)).append('m');
            }
        }
        return text.toString();
    }

    /**
     * 实体是否隐身。
     *
     * <p>{@code Entity#isInvisible} 目前不在映射表里：未混淆版本（26.x）按规范名直接能调到，
     * 混淆版本读不到就一律当作"可见"——宁可在老版本上多显示几个标签，也不因为读不到而把实体全滤掉。
     */
    private static boolean isInvisible(GameBridge bridge, Object entity) {
        Object value = bridge.callMapped(entity, ClassType.ENTITY, "isInvisible");
        return value instanceof Boolean && ((Boolean) value).booleanValue();
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

    /** 是否为生物实体（按 LivingEntity 映射类做 instanceof，避免写版本分支）。 */
    private static boolean isLiving(GameBridge bridge, Object entity) {
        ClassLoader loader = bridge.gameLoader();
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
        System.out.println("[nocturne] NameTags: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
