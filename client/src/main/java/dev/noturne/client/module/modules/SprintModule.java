package dev.noturne.client.module.modules;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;

/**
 * 启用期间持续让玩家处于疾跑状态。
 *
 * <p>每 tick 调用真实的 {@code Entity.setSprinting(boolean)}，因此服务端收到的是普通
 * 疾跑数据包，而非任何合成出来的动作。
 */
public final class SprintModule extends Module {

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "Sprint";
    }

    /** 归入“移动”分组。 */
    @Override
    public Category category() {
        return Category.MOVEMENT;
    }

    /**
     * 每 tick 强制疾跑。
     *
     * <p>bridge / player 为 null 时表示客户端未就绪或未进入世界，直接跳过；
     * 疾跑标志由游戏自身在下个 tick 结算，无需在此处理疾跑被服务端拒绝的情况。
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
        bridge.callMapped(player, ClassType.ENTITY, "setSprinting", Boolean.TRUE);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
