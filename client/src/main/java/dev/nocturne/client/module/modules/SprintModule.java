package dev.nocturne.client.module.modules;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;

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

    /** 水平速度平方的阈值：明显大于 0 才算"在移动"（避开浮点噪声）。 */
    private static final double MOVING_EPSILON = 1.0e-6d;

    /**
     * 每 tick 在玩家"想跑"时强制疾跑。
     *
     * <p>不能无条件置位：游戏本身有一整套疾跑取消条件（潜行、后退、撞墙、被攻击、饥饿），
     * 每 tick 强行点真是把它们全部覆盖掉——玩家一潜行就变成贴地疾跑，饥饿值耗尽也照跑。
     * 这里只补游戏不会自己点的两种情况之外的部分：确认玩家在水平移动、且没有蹲下。
     *
     * <p>bridge / player 为 null 时表示客户端未就绪或未进入世界，直接跳过。
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
        if (Boolean.TRUE.equals(bridge.callMapped(player, ClassType.ENTITY, "isShiftKeyDown"))) {
            // 蹲着不能跑：这一条游戏自己也会判，但我们在它之后写入，必须自己拦。
            return;
        }
        if (!isMovingHorizontally(bridge, player)) {
            // 站着不动：不必也不该疾跑。
            return;
        }
        bridge.callMapped(player, ClassType.ENTITY, "setSprinting", Boolean.TRUE);
    }

    /** 水平速度是否明显非零（说明玩家正朝某个方向移动）。 */
    private static boolean isMovingHorizontally(GameBridge bridge, Object player) {
        Object delta = bridge.callMapped(player, ClassType.ENTITY, "getDeltaMovement");
        if (delta == null) {
            return false;
        }
        Object x = bridge.readField(delta, ClassType.VEC3, "x");
        Object z = bridge.readField(delta, ClassType.VEC3, "z");
        if (!(x instanceof Number) || !(z instanceof Number)) {
            return false;
        }
        double dx = ((Number) x).doubleValue();
        double dz = ((Number) z).doubleValue();
        return dx * dx + dz * dz > MOVING_EPSILON;
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
