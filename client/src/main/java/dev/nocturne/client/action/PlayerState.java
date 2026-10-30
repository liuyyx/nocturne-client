package dev.nocturne.client.action;

/**
 * 一次读取得到的玩家共享状态快照。
 *
 * <p>对象不可变，可安全交给同一 tick 内的多个模块，避免每个模块各自读一套不一致的坐标/朝向。
 * 快照不保证跨 tick 有效；{@link #player()} 仍是游戏对象，不延长其生命周期。
 */
public final class PlayerState {

    private static final PlayerState EMPTY = new PlayerState(null, 0.0, 0.0, 0.0,
            0.0f, 0.0f, false);

    private final Object player;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final boolean onGround;

    PlayerState(Object player, double x, double y, double z,
                float yaw, float pitch, boolean onGround) {
        this.player = player;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
    }

    /** 世界未就绪时的唯一空快照。 */
    public static PlayerState empty() {
        return EMPTY;
    }

    public boolean present() {
        return player != null;
    }

    public Object player() {
        return player;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public boolean onGround() {
        return onGround;
    }
}
