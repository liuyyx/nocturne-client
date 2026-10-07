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

/**
 * 方块搜索：后台扫描区块，对名单方块画描边框（可选引线）。
 *
 * <p>行为对齐 OpenVape render.Search（只搬行为，不搬代码；注意它不是 world.XRay）：
 * 扫描半径、仅洞穴、引线开关。对方的方块名单走 SearchManager/SearchBlock 外部帧配置、
 * 不在 Value 体系，我方同样不设可见名单项（待名单值类型落地后补）。对方有个
 * “-”占位 Number（5/5/5，未进 addValue），按隐藏行为处理、不设项。
 * 版本门走表驱动——区块/方块面缺失的版本一次性日志 + 跳过。
 *
 * <p>绘制挂钩说明：后台扫描线程 + 双绘制路径（实例化批量/逐盒）的真正投递点尚未接入，
 * 当前先订阅 {@link RenderEvent} 做 per-frame 的门检查，
 * 实际扫描与画框待世界渲染挂钩落地后补齐。模块开关/参数/门逻辑现在即可验收。
 */
public final class SearchModule extends Module {

    /** 扫描半径（对方默认 50，范围 1–100）。 */
    private final NumberValue range = add(new NumberValue("Range", 50.0, 1.0, 100.0, 1.0));
    /** 仅搜索暴露于空气的矿石。 */
    private final BooleanValue onlyCaves = add(new BooleanValue("Only caves", false));
    /** 给命中的方块加引线。 */
    private final BooleanValue useTracers = add(new BooleanValue("Use tracers", false));

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;
    /** 上一帧命中数（HUD/诊断用；扫描落地前恒 0）。 */
    private int lastHitCount;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "Search";
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
        lastHitCount = 0;
        subscribe();
    }

    /** 禁用时退订渲染事件（后台扫描线程由挂钩落地后的实现负责停止，当前无副作用可撤）。 */
    @Override
    protected void onDisable() {
        unsubscribe();
        lastHitCount = 0;
    }

    /** 每 tick 确保订阅存在（总线晚于模块启用才就绪时补上）。 */
    @Override
    public void onTick() {
        if (renderSubscription == null) {
            subscribe();
        }
    }

    /** @return 上一帧命中数（扫描落地前供验收用）。 */
    public int lastHitCount() {
        return lastHitCount;
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
     * 每渲染帧做门检查。
     *
     * <p>表驱动门：区块面缺失的版本一次性日志 + 跳过。后台扫描线程与双绘制路径
     * 待世界渲染挂钩落地后补齐；当前只做门检查，不碰任何游戏状态。
     */
    private void onRender(RenderEvent event) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        if (bridge.player() == null) {
            lastHitCount = 0;
            return;
        }
        if (!hasChunkFace(bridge)) {
            logGateOnce("区块面缺失（版本无该成员），Search 已禁用");
            lastHitCount = 0;
            return;
        }
        lastHitCount = 0;
        // 后台扫描 + 描边框/引线待世界渲染挂钩落地后补齐；此处只做门检查。
    }

    /** 区块面是否存在：ClientLevel 类可解析即认为可用。 */
    private static boolean hasChunkFace(GameBridge bridge) {
        Object player = bridge.player();
        ClassLoader loader = player != null && player.getClass().getClassLoader() != null
                ? player.getClass().getClassLoader()
                : SearchModule.class.getClassLoader();
        // 候选名逐个试（原版混淆名 → intermediary → SRG → NeoForge → 规范名），第一个加载成功者胜出。
        for (String mapped : bridge.mapping().classNameCandidates(ClassType.CLIENT_LEVEL)) {
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

    /** 表驱动门日志：缺成员版本只报一次。 */
    private void logGateOnce(String reason) {
        if (gateLogged) {
            return;
        }
        gateLogged = true;
        System.out.println("[nocturne] Search: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
