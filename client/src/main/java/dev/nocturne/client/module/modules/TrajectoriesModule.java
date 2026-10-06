package dev.nocturne.client.module.modules;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.RenderEvent;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
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
 * <p>绘制挂钩说明：世界空间抛物线 + 落点十字的真正投递点（世界渲染 pass）尚未接入，
 * 当前先订阅 {@link RenderEvent} 做 per-frame 的持有判定与弹道模拟，
 * 实际画线待世界绘制挂钩落地后补齐。模块开关/参数/门逻辑现在即可验收。
 */
public final class TrajectoriesModule extends Module {

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
        try {
            String mapped = bridge.mapping().className(ClassType.ITEM_STACK);
            if (mapped == null) {
                return false;
            }
            Object player = bridge.player();
            ClassLoader loader = player != null && player.getClass().getClassLoader() != null
                    ? player.getClass().getClassLoader()
                    : TrajectoriesModule.class.getClassLoader();
            return Class.forName(mapped, false, loader) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 弹道模拟（纯数学，不碰游戏状态）。
     *
     * <p>对方行为：初速按拉弓时长折算（蓄满 1.0），重力每 tick 下拉，
     * 最多 40 步，遇方块/实体停画。本方法当前只做“是否持有可投掷物”判定
     * （手持物品栈非空即认为可画，返回固定步数），真实步进模拟与碰撞待
     * 手持物品栏面 + 方块碰撞面落地后补齐。Ghost Bow Charge 开时未拉弓也返回步数。
     *
     * @return 本帧应画的模拟步数；0 表示不画
     */
    private int simulate(GameBridge bridge, Object player) {
        Object held = heldStack(bridge, player);
        if (held == null && !ghostBowCharge.get()) {
            return 0;
        }
        // 步进模拟与碰撞待面落地；当前返回哨兵步数表示“持有判定通过”。
        return 40;
    }

    /**
     * 读主手物品栈；物品栏面缺失时返回 {@code null}（Ghost 开时仍可画虚线）。
     */
    private static Object heldStack(GameBridge bridge, Object player) {
        try {
            // 规范面：Player#getItem() 系方法在表里是 Inventory#getItem；
            // 玩家手持栈的直接面尚未入库，此处保守返回 null（= 未持有）。
            return null;
        } catch (Throwable t) {
            return null;
        }
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
