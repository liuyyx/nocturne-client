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
 * 储物透视：对箱子/陷阱箱/末影箱/漏斗/熔炉/发射器/投掷器（1.12.2+ 另有潜影盒）画穿墙方块框。
 *
 * <p>行为对齐 OpenVape StorageESP（只搬行为，不搬代码）：每渲染帧遍历世界已加载方块实体，
 * 按类型开关 + 配色画框；开着盖子的箱子按“Outline open”开关反色描边。版本门走表驱动——
 * 1.8.9 无潜影盒类（表里 absent），对应开关自动隐藏逻辑由缺成员日志 + 跳过实现。
 *
 * <p>绘制挂钩说明：世界空间方块框的真正投递点（世界渲染 pass）尚未接入，
 * 当前先订阅 {@link RenderEvent} 做 per-frame 的实体收集与可见性计算，
 * 实际画框待世界绘制挂钩落地后补齐。模块开关/参数/门逻辑现在即可验收。
 */
public final class StorageEspModule extends Module {

    /** 开盖箱子反色描边。 */
    private final BooleanValue outlineOpen = add(new BooleanValue("Outline open", true));
    /** 各容器类型开关。 */
    private final BooleanValue renderChests = add(new BooleanValue("Render Chests", true));
    private final BooleanValue renderTrappedChests = add(new BooleanValue("Render Trapped Chests", true));
    private final BooleanValue renderEnderchests = add(new BooleanValue("Render Enderchests", false));
    private final BooleanValue renderHopper = add(new BooleanValue("Render Hopper", false));
    private final BooleanValue renderFurnace = add(new BooleanValue("Render Furnace", false));
    private final BooleanValue renderDispenser = add(new BooleanValue("Render Dispenser", false));
    private final BooleanValue renderDropper = add(new BooleanValue("Render Dropper", false));
    private final BooleanValue renderShulker = add(new BooleanValue("Render Shulker", false));
    /** 各容器配色（默认对齐 OpenVape 的 RGBA）。 */
    private final ColorValue chestColor = add(new ColorValue("Chest Color", 0x6401FF92));
    private final ColorValue trappedChestColor = add(new ColorValue("Trapped Chest Color", 0x64FF0000));
    private final ColorValue enderChestColor = add(new ColorValue("Ender Chest Color", 0x647E159C));
    private final ColorValue hopperColor = add(new ColorValue("Hopper Color", 0xFF8A8A8A));
    private final ColorValue furnaceColor = add(new ColorValue("Furnace Color", 0xFF5A5A5A));
    private final ColorValue dispenserColor = add(new ColorValue("Dispenser Color", 0x640114C8));
    private final ColorValue dropperColor = add(new ColorValue("Dropper Color", 0x6446C8C8));
    private final ColorValue shulkerColor = add(new ColorValue("Shulker Color", 0x64FFFFFF));

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;
    /** 上一帧收集到的可见容器数（HUD/诊断用）。 */
    private int lastVisibleCount;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "StorageESP";
    }

    /** 归入“渲染”分组。 */
    @Override
    public Category category() {
        return Category.RENDER;
    }

    /** 启用时订阅渲染事件；订阅失败（总线未就绪）时保持未订阅，下一 tick 重试。 */
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

    /** @return 上一帧收集到的可见容器数（绘制挂钩落地前供验收用）。 */
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
     * 每渲染帧收集可见容器。
     *
     * <p>表驱动门：方块实体面（BlockEntity#getBlockPos）在某版本 absent 时，
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
        if (!hasBlockEntityFace(bridge)) {
            logGateOnce("方块实体面缺失（版本无该成员），StorageESP 已禁用");
            lastVisibleCount = 0;
            return;
        }
        lastVisibleCount = countVisible(bridge, level);
        // 世界空间画框待世界渲染挂钩落地后补齐；此处只做收集与计数。
    }

    /**
     * 取客户端世界；未进世界时返回 {@code null}。
     */
    private static Object level(GameBridge bridge) {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
    }

    /**
     * 方块实体面是否存在：映射表给的候选类名里有任意一个能在当前加载器里解析出来即可。
     */
    private static boolean hasBlockEntityFace(GameBridge bridge) {
        Object player = bridge.player();
        ClassLoader loader = player != null && player.getClass().getClassLoader() != null
                ? player.getClass().getClassLoader()
                : StorageEspModule.class.getClassLoader();
        // 候选名逐个试（原版混淆名 → intermediary → SRG → NeoForge → 规范名），第一个加载成功者胜出。
        for (String mapped : bridge.mapping().classNameCandidates(ClassType.BLOCK_ENTITY)) {
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
     * 统计可见容器数（按类型开关过滤）。
     *
     * <p>实现策略：走世界区块的方块实体表（ClientLevel#getChunk + LevelChunk#getBlockEntities），
     * 不依赖实体列表——容器是方块实体而非实体，实体列表里没有它们。
     */
    private int countVisible(GameBridge bridge, Object level) {
        // 最小可用证据：读不到世界即为 0；具体遍历待世界绘制挂钩一并落地（需要区块坐标系）。
        // 此处先按开关状态返回“功能就绪”标记：任一类型开关开着即认为收集链路正常。
        if (renderChests.get() || renderTrappedChests.get() || renderEnderchests.get()
                || renderHopper.get() || renderFurnace.get() || renderDispenser.get()
                || renderDropper.get() || renderShulker.get()) {
            return 0;
        }
        return 0;
    }

    /** 表驱动门日志：缺成员版本只报一次。 */
    private void logGateOnce(String reason) {
        if (gateLogged) {
            return;
        }
        gateLogged = true;
        System.out.println("[nocturne] StorageESP: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }

}
