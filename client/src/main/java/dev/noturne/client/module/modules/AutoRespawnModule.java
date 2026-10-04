package dev.noturne.client.module.modules;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;

/**
 * 玩家死亡后立即执行重生，而不是停留在死亡界面等待手动点击。
 *
 * <p>实现方式是经映射读取 {@code Entity.isDead}，再调用本地玩家的
 * {@code respawnPlayer()}；两者都是普通游戏调用，与玩家手动点击“Respawn”在服务端看来
 * 完全一致，检测上不可区分。
 */
public final class AutoRespawnModule extends Module {

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
     * 每 tick 检查死亡标志并触发重生。
     *
     * <p>两处提前返回分别应对客户端尚未就绪（bridge 为 null）与尚未进入世界
     * （player 为 null）；此时代码路径不可用，跳过即可——注册表的激活闸门已覆盖大部分
     * 这种情况，这里的检查只是额外的兜底。
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
        Object dead = bridge.readField(player, ClassType.ENTITY, "isDead");
        if (Boolean.TRUE.equals(dead)) {
            bridge.callMapped(player, ClassType.LOCAL_PLAYER, "respawnPlayer");
        }
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
