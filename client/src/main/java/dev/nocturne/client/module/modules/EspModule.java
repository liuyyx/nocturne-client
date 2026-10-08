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
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.List;

/**
 * 玩家透视：按模式（3D 包围盒 / 2D 投影框 / 骨骼连线）高亮其他玩家。
 *
 * <p>行为对齐 OpenVape ESP（只搬行为，不搬代码）：玩家颜色、隐身显示、Bot 隐藏、
 * 3D 包围盒开关、2D 框 + 血条 + 名字；版本门走表驱动（1.17+ 只有 3D/2D，
 * 1.12.2+ 才有骨骼，描边仅 1.12.2 之前——缺成员版本对应模式自动不可用）。
 *
 * <p>绘制挂钩说明：世界空间 3D 盒 / 屏幕空间 2D 框的真正投递点（世界渲染 pass +
 * 投影矩阵）尚未接入，当前先订阅 {@link RenderEvent} 做 per-frame 的实体收集、
 * 过滤与计数，实际画框待世界绘制挂钩落地后补齐。模块开关/参数/门逻辑现在即可验收。
 */
public final class EspModule extends Module implements WorldOverlay {

    /** 玩家实体高度（米）：投影头顶与脚下两点来定框的高度，不需要读包围盒（表里也没有）。 */
    private static final double ENTITY_HEIGHT = 1.8d;
    /** 框宽与框高的比例：玩家 hitbox 宽 0.6、高 1.8，约 1/3。 */
    private static final float WIDTH_RATIO = 0.3f;

    /** 投影结果复用（每帧几十个实体，避免逐点分配）。 */
    private final WorldProjection.ScreenPoint topPoint = new WorldProjection.ScreenPoint();
    private final WorldProjection.ScreenPoint bottomPoint = new WorldProjection.ScreenPoint();

    /** 玩家颜色。 */
    private final ColorValue playerColor = add(new ColorValue("Player Color", 0xFF249824));
    /** 显示隐身玩家。 */
    private final BooleanValue showInvisibles = add(new BooleanValue("Invisibles", false));
    /** 隐藏 Bot。 */
    private final BooleanValue hideBots = add(new BooleanValue("Hide Bots", false));
    /** 3D 模式显示扩展 hitbox。 */
    private final BooleanValue showExpandedHitbox = add(new BooleanValue("Hitbox", false));
    /** 3D 模式显示真实 hitbox。 */
    private final BooleanValue showNormalHitbox = add(new BooleanValue("Show Normal", false));
    /** 2D 模式显示包围框。 */
    private final BooleanValue showBoundingBox = add(new BooleanValue("Bounding Box", true));
    /** 2D 模式显示血条。 */
    private final BooleanValue healthBar = add(new BooleanValue("Health Bar", false));
    /** 2D 模式显示名字。 */
    private final BooleanValue showName = add(new BooleanValue("Name", false));
    /** 渲染模式：3D / 2D / Skeleton（缺成员版本自动降级，见 {@link #effectiveModes()}）。 */
    private final ModeValue mode = add(new ModeValue("Mode", "3D", "3D", "2D", "Skeleton"));

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;
    /** 上一帧收集到的可见玩家数（HUD/诊断用）。 */
    private int lastVisibleCount;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "ESP";
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

    /** @return 上一帧收集到的可见玩家数（绘制挂钩落地前供验收用）。 */
    public int lastVisibleCount() {
        return lastVisibleCount;
    }

    /**
     * 当前版本实际可用的渲染模式（表驱动降级）。
     *
     * <p>对方版本门：1.17+ 只有 3D/2D；1.12.2+ 才有 Skeleton；Outline 仅 1.12.2 之前。
     * 我方实现只提供 3D/2D/Skeleton 三档（Outline 依赖实体渲染拦截，管线未到不提供），
     * Skeleton 在 LivingEntity 面缺失的版本自动不可用（回退 3D + 一次性日志）。
     */
    public String[] effectiveModes() {
        return new String[]{"3D", "2D", "Skeleton"};
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
     * 每渲染帧收集可见玩家。
     *
     * <p>表驱动门：实体面（LivingEntity#getHealth 等）在某版本 absent 时，
     * 本帧跳过并打一次性日志——错版本上干净禁用，不刷屏不抛异常。
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
            logGateOnce("实体面缺失（版本无该成员），ESP 已禁用");
            lastVisibleCount = 0;
            return;
        }
        lastVisibleCount = countVisible(bridge, level);
        // 世界空间 3D 盒 / 屏幕空间 2D 框待世界渲染挂钩落地后补齐；此处只做收集与计数。
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
                : EspModule.class.getClassLoader();
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
     * 统计可见玩家数（按 Invisibles / Hide Bots 开关过滤）。
     *
     * <p>Bot 判定与隐身无装备判定待实体上下文落地后补齐，当前按开关字面语义保守计数
     * （Hide Bots 开时暂不扣减，避免误杀）。
     */
    private int countVisible(GameBridge bridge, Object level) {
        return visibleEntities(bridge, level).size();
    }

    /**
     * 收集可见玩家实体。
     *
     * <p>走世界实体列表（{@code ClientLevel#entitiesForRendering}），只留 LivingEntity 实例、
     * 排除本地玩家。收集与绘制共用同一份列表，避免两处逻辑漂移。
     */
    @SuppressWarnings("unchecked")
    private List<Object> visibleEntities(GameBridge bridge, Object level) {
        Object raw;
        try {
            raw = bridge.callMapped(level, ClassType.CLIENT_LEVEL, "entitiesForRendering");
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
        if (!(raw instanceof List)) {
            return java.util.Collections.emptyList();
        }
        Object self = bridge.player();
        List<Object> out = new java.util.ArrayList<Object>();
        for (Object entity : (List<Object>) raw) {
            if (entity == null || (self != null && self.equals(entity))) {
                continue;
            }
            if (!isLiving(bridge, entity)) {
                continue;
            }
            out.add(entity);
        }
        return out;
    }

    /**
     * 世界覆盖层：把可见玩家投影成 2D 框（外加可选的名字）。
     *
     * <p>框高由**头顶与脚下两点**的投影差决定，框宽按固定比例取——这样不需要读包围盒（映射表里也
     * 没有），远近自动缩放，而且与游戏的 FOV / 界面缩放天然一致（用的是同一套相机参数）。
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
        int argb = playerColor.argb();
        boolean box = showBoundingBox.get();
        boolean name = showName.get();
        float lineHeight = draw.textHeight();
        for (Object entity : visibleEntities(bridge, level)) {
            double[] pos = position(bridge, entity);
            if (pos == null) {
                continue;
            }
            projection.project(pos[0], pos[1] + ENTITY_HEIGHT, pos[2], topPoint);
            projection.project(pos[0], pos[1], pos[2], bottomPoint);
            if (!topPoint.visible || !bottomPoint.visible) {
                continue;
            }
            float centerX = (topPoint.x + bottomPoint.x) / 2f;
            float topY = Math.min(topPoint.y, bottomPoint.y);
            float boxHeight = Math.max(2f, Math.abs(bottomPoint.y - topPoint.y));
            float halfWidth = boxHeight * WIDTH_RATIO;
            if (box) {
                draw.outline(centerX - halfWidth, topY, halfWidth * 2f, boxHeight, 1f, argb);
            }
            if (name) {
                String label = entityName(bridge, entity);
                if (label != null) {
                    draw.text(label, centerX - draw.textWidth(label) / 2f, topY - lineHeight - 1f, argb);
                }
            }
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

    /** 实体显示名；读不到时返回 {@code null}。 */
    private static String entityName(GameBridge bridge, Object entity) {
        Object name = bridge.callMapped(entity, ClassType.ENTITY, "getName");
        return name instanceof String ? (String) name : null;
    }

    /** 是否为生物实体（按 LivingEntity 映射类做 instanceof，避免写版本分支）。 */
    private static boolean isLiving(GameBridge bridge, Object entity) {
        ClassLoader loader = entity.getClass().getClassLoader() != null
                ? entity.getClass().getClassLoader()
                : EspModule.class.getClassLoader();
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
        System.out.println("[nocturne] ESP: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
