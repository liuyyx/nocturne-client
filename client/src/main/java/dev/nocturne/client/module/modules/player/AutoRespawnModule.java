package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;

/**
 * 玩家死亡后执行重生，而不是停留在死亡界面等待手动点击。
 *
 * <p>实现方式是经映射读取 {@code Entity.isDead}，再调用本地玩家的
 * {@code respawnPlayer()}；两者都是普通游戏调用，与玩家手动点击“Respawn”在服务端看来
 * 完全一致，检测上不可区分。
 *
 * <p>驱动契约（与 {@link ModuleRegistry} 的激活闸门对齐）：本模块覆写
 * {@link #runsWhileInactive()} 返回 {@code true}，因为它的触发条件恰恰是“玩家已死亡”，
 * 而注册表正是在死亡/重生界面期间关闭闸门。若不做豁免，{@code onTick} 永远不会被调用，
 * 自动重生 100% 失效。豁免意味着闸门关闭期间本模块不会被挂起，其 {@code onTick} 必须自行
 * 完成全部前置判空。
 *
 * <p>重生闩锁与冷却：同一次死亡只请求一次重生（闩锁在玩家脱离死亡状态后复位），并设有最小
 * 重试间隔，避免每 tick（20 次/秒）重复发送重生包——否则服务端的频率限制会让我们与手动点击
 * 的行为出现明显差异。
 */
public final class AutoRespawnModule extends Module {

    /** 两次重生请求之间的最小间隔（毫秒）：即使玩家状态抖动，也不会退化回每 tick 发一次。 */
    private static final long MIN_RETRY_INTERVAL_MS = 1000L;

    /**
     * 本次死亡周期内是否已发出过重生请求；玩家脱离死亡状态后复位。
     *
     * <p>只用于诊断（避免重复刷屏），<b>不再是"只发一次"的闩锁</b>——见 {@link #onTick}。
     */
    private boolean respawnRequested;
    /** respawnPlayer 调不动是否已打过日志（表驱动门：缺成员版本只报一次）。 */
    private boolean respawnLogOnce;
    /** 上次发起重生请求的时刻（{@link System#currentTimeMillis()}）；初始 0 表示从未请求。 */
    private long lastRequestMs;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "AutoRespawn";
    }

    /** 归入“玩家自身”分组。 */
    @Override
    public Category category() {
        return Category.PLAYER;
    }

    /**
     * 豁免注册表的激活闸门：死亡/重生界面期间注册表会关闭闸门，而本模块的触发条件正存在于该时段。
     *
     * @return 恒为 {@code true}
     */
    @Override
    public boolean runsWhileInactive() {
        return true;
    }

    /**
     * 每 tick 检查死亡标志，按闩锁 + 冷却至多请求一次重生。
     *
     * <p>前置判空（bridge / player 为 null）表示客户端尚未就绪或尚未进入世界，此时跳过。
     */
    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object player = bridge.player();
        if (player == null) {
            return;
        }
        if (!isDead(bridge, player)) {
            // 玩家已脱离死亡状态（服务端已处理重生）：复位状态，为下一次死亡做准备。
            respawnRequested = false;
            respawnLogOnce = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRequestMs < MIN_RETRY_INTERVAL_MS) {
            // 冷却未到：避免状态抖动导致连续请求（服务端对重生包有频率限制）。
            return;
        }
        boolean retry = respawnRequested;
        lastRequestMs = now;
        respawnRequested = true;
        // 发出后**不认定成功**：某些版本的表里没有 respawnPlayer（absent），调用会静默返回 null。
        // 旧代码在调用前就把闩锁置位并永不复位，于是"发不出去"和"已经发过"再也分不开——
        // 除 1.8.9/1.12.2 之外的所有版本都自动重生失效。这里改成按冷却重试，直到玩家复活。
        bridge.callMapped(player, ClassType.LOCAL_PLAYER, "respawnPlayer");
        if (!respawnLogOnce) {
            respawnLogOnce = true;
            System.out.println("[nocturne] AutoRespawn: respawn " + (retry ? "retry" : "request")
                    + " sent (重试间隔 " + MIN_RETRY_INTERVAL_MS + "ms"
                    + "；该版本若无 respawnPlayer 会持续重试到复活)");
        }
    }

    /**
     * 玩家是否处于死亡状态。
     *
     * <p>两条判据取并集：{@code Entity#isDead} 在 26.2/26.3 的映射表里是 absent（字段被改名），
     * 只认它会让这些版本永远判为"活着"、自动重生整条失效；血量 {@code getHealth() <= 0} 三代都有，
     * 作为兜底。两者都读不到时判为"活着"——不主动发重生包比乱发安全。
     */
    private static boolean isDead(GameBridge bridge, Object player) {
        if (Boolean.TRUE.equals(bridge.readField(player, ClassType.ENTITY, "isDead"))) {
            return true;
        }
        Object health = bridge.callMapped(player, ClassType.LIVING_ENTITY, "getHealth");
        return health instanceof Number && ((Number) health).doubleValue() <= 0d;
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
