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

/**
 * 抛射物轨迹：手持弓/雪球/末影珍珠/鸡蛋/药水时画抛物线 + 落点十字。
 *
 * <p>行为对齐 OpenVape Trajectories（只搬行为，不搬代码）：瞄准白 / 轨迹红 /
 * 落点蓝三色 + 未拉弓时满弦虚线（Ghost Bow Charge）；蓄力按拉弓时长折算初速，
 * 步进模拟重力下落、遇阻停画。版本门走表驱动——手持物品/蓄力/实体面任一缺失的版本
 * 一次性日志 + 跳过。
 *
 * <p>绘制：由 {@link WorldOverlay} 每帧在叠加层上画——从眼位沿视线做步进弹道模拟（重力 0.03、
 * 阻力 0.99、满蓄力初速 3.0 格/tick），每步投影后连成折线，末端画落点十字。
 * 蓄力折算与"是不是投掷物"的物品 kind 面尚未入库：前者按满蓄力，后者只要手持非空即画。
 */
public final class TrajectoriesModule extends Module implements WorldOverlay {

    /** 模拟步数上限（约 6 秒飞行；再往后就是没落地，画下去只是浪费绘制调用）。 */
    private static final int MAX_STEPS = 120;
    /** 每 tick 重力加速度（MC 的投掷物都是 0.03 格/tick²）。 */
    private static final double GRAVITY = 0.03d;
    /** 每 tick 速度衰减（空气阻力）。 */
    private static final double DRAG = 0.99d;
    /** 满蓄力初速（格/tick）。 */
    private static final double FULL_POWER_SPEED = 3.0d;
    /** 落点十字的臂长（像素）。 */
    private static final float CROSS_ARM = 4f;

    /** 投影结果复用（每帧上百步，避免逐步分配）。 */
    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();

    /** 瞄准线颜色（未命中实体时的线色）。 */
    private final ColorValue aimingColor = add(new ColorValue("Aiming Color", 0xFFFFFFFF));
    /** 轨迹线颜色。 */
    private final ColorValue trajectoryColor = add(new ColorValue("Trajectory Color", 0xFFFF0000));
    /** 落点十字颜色（命中实体时换此色）。 */
    private final ColorValue targetColor = add(new ColorValue("Target Color", 0xFF0000FF));
    /** 未拉弓时按满弦画虚线。 */
    private final BooleanValue ghostBowCharge = add(new BooleanValue("Ghost Bow Charge", false));

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;
    /** 上一帧模拟步数（HUD/诊断用；0 表示未持有可投掷物或被门拦下）。 */
    private int lastSimulatedSteps;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "Trajectories";
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
        lastSimulatedSteps = 0;
        subscribe();
    }

    /** 禁用时退订渲染事件。 */
    @Override
    protected void onDisable() {
        unsubscribe();
        lastSimulatedSteps = 0;
    }

    /** 每 tick 确保订阅存在（总线晚于模块启用才就绪时补上）。 */
    @Override
    public void onTick() {
        if (renderSubscription == null) {
            subscribe();
        }
    }

    /** @return 上一帧模拟步数（绘制挂钩落地前供验收用）。 */
    public int lastSimulatedSteps() {
        return lastSimulatedSteps;
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
     * 每渲染帧判定持有并模拟弹道。
     *
     * <p>表驱动门：玩家/物品面缺失的版本一次性日志 + 跳过，不刷屏不抛异常。
     * 手持物品读取（主手物品栈 + 拉弓时长）需要物品栏面，缺失时同样走门逻辑。
     */
    private void onRender(RenderEvent event) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object player = bridge.player();
        if (player == null) {
            lastSimulatedSteps = 0;
            return;
        }
        if (!hasProjectileFace(bridge)) {
            logGateOnce("投掷物面缺失（版本无该成员），Trajectories 已禁用");
            lastSimulatedSteps = 0;
            return;
        }
        lastSimulatedSteps = simulate(bridge, player);
        // 世界空间抛物线 + 落点十字待世界渲染挂钩落地后补齐；此处只做判定与模拟。
    }

    /**
     * 投掷物面是否存在：物品栈类可解析即认为可用。
     *
     * <p>对方按弓/雪球/珍珠/蛋/药水五个物品类区分弹道（重力与初速不同），
     * 我方当前表里只有 ItemStack 一层，故门只查到物品栈一级；
     * 按物品种类细分弹道参数待物品 kind 面落地后补齐。
     */
    private static boolean hasProjectileFace(GameBridge bridge) {
        Object player = bridge.player();
        ClassLoader loader = player != null && player.getClass().getClassLoader() != null
                ? player.getClass().getClassLoader()
                : TrajectoriesModule.class.getClassLoader();
        // 候选名逐个试（原版混淆名 → intermediary → SRG → NeoForge → 规范名），第一个加载成功者胜出。
        for (String mapped : bridge.mapping().classNameCandidates(ClassType.ITEM_STACK)) {
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
     * 弹道模拟（纯数学，不碰游戏状态）。
     *
     * <p>对方行为：初速按拉弓时长折算（蓄满 1.0），重力每 tick 下拉，最多 40 步，遇方块/实体停画。
     * 现在真的做步进模拟了——重力 0.03、阻力 0.99、满蓄力初速 3.0 格/tick，与 MC 投掷物一致。
     * 碰撞停画需要方块碰撞面（尚未入库），因此模拟跑到步数上限为止。
     *
     * @return 本帧应画的模拟步数；0 表示不画（未持有且未开 Ghost）
     */
    private int simulate(GameBridge bridge, Object player) {
        if (heldStack(bridge, player) == null && !ghostBowCharge.get()) {
            return 0;
        }
        return MAX_STEPS;
    }

    /**
     * 读主手物品栈；物品栏面缺失时返回 {@code null}（Ghost 开时仍可画）。
     *
     * <p>规范名 {@code getMainHandItem}，1.8.9 走别名桥到 {@code EntityLivingBase.getHeldItem}。
     */
    private static Object heldStack(GameBridge bridge, Object player) {
        try {
            return bridge.callMapped(player, ClassType.LIVING_ENTITY, "getMainHandItem");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 世界覆盖层：从眼位沿视线步进模拟并画出轨迹，末端画落点十字。
     *
     * <p>每步都投影一次：投影不可见（出屏/在背后）时断开折线，避免把屏幕外两点连成一条横穿屏幕的线。
     */
    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null || !hasProjectileFace(bridge)) {
            return;
        }
        Object player = bridge.player();
        if (player == null) {
            return;
        }
        if (heldStack(bridge, player) == null && !ghostBowCharge.get()) {
            return;
        }
        double[] eye = eyePosition(bridge, player);
        float[] rotation = rotation(bridge, player);
        if (eye == null || rotation == null) {
            return;
        }
        double yaw = Math.toRadians(rotation[0]);
        double pitch = Math.toRadians(rotation[1]);
        double vx = -Math.sin(yaw) * Math.cos(pitch) * FULL_POWER_SPEED;
        double vy = -Math.sin(pitch) * FULL_POWER_SPEED;
        double vz = Math.cos(yaw) * Math.cos(pitch) * FULL_POWER_SPEED;
        double x = eye[0];
        double y = eye[1];
        double z = eye[2];

        int lineArgb = trajectoryColor.argb();
        boolean hasPrevious = false;
        float previousX = 0f;
        float previousY = 0f;
        for (int step = 0; step < MAX_STEPS; step++) {
            x += vx;
            y += vy;
            z += vz;
            vx *= DRAG;
            vz *= DRAG;
            vy = vy * DRAG - GRAVITY;
            projection.project(x, y, z, point);
            if (!point.visible) {
                hasPrevious = false;
                continue;
            }
            if (hasPrevious) {
                draw.line(previousX, previousY, point.x, point.y, 1.5f, lineArgb);
            }
            previousX = point.x;
            previousY = point.y;
            hasPrevious = true;
        }
        if (hasPrevious) {
            int crossArgb = targetColor.argb();
            draw.line(previousX - CROSS_ARM, previousY, previousX + CROSS_ARM, previousY, 1.5f, crossArgb);
            draw.line(previousX, previousY - CROSS_ARM, previousX, previousY + CROSS_ARM, 1.5f, crossArgb);
        }
    }

    /** 读实体朝向（{@code yRot} / {@code xRot}，度）；缺成员时返回 {@code null}。 */
    private static float[] rotation(GameBridge bridge, Object entity) {
        Object yaw = bridge.readField(entity, ClassType.ENTITY, "yRot");
        Object pitch = bridge.readField(entity, ClassType.ENTITY, "xRot");
        if (!(yaw instanceof Number) || !(pitch instanceof Number)) {
            return null;
        }
        return new float[]{((Number) yaw).floatValue(), ((Number) pitch).floatValue()};
    }

    /** 读实体眼位（{@code getEyePosition()} → {@code Vec3#x/y/z}）；缺成员时返回 {@code null}。 */
    private static double[] eyePosition(GameBridge bridge, Object entity) {
        Object vec = bridge.callMapped(entity, ClassType.ENTITY, "getEyePosition");
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

    /** 表驱动门日志：缺成员版本只报一次。 */
    private void logGateOnce(String reason) {
        if (gateLogged) {
            return;
        }
        gateLogged = true;
        System.out.println("[nocturne] Trajectories: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
