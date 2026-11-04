package dev.nocturne.client.action;

/**
 * 一次读取得到的玩家共享状态快照。
 *
 * <p>对象不可变，可安全交给同一 tick 内的多个模块，避免每个模块各自读一套不一致的坐标/朝向。
 * 快照不保证跨 tick 有效；{@link #player()} 仍是游戏对象，不延长其生命周期。
 */
public final class PlayerState {

    private static final PlayerState EMPTY = new PlayerState(null, 0.0, 0.0, 0.0,
            0.0f, 0.0f, false, -1, 0, 0, 0,
            0.0f, 0.0f, 1.0f, false, false, false);

    private final Object player;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final boolean onGround;
    private final int selectedSlot;
    private final int mainHandCount;
    private final int mainHandMaxStack;
    private final int offHandCount;
    private final float health;
    private final float absorption;
    private final float attackStrengthScale;
    private final boolean sprinting;
    private final boolean shifting;
    private final boolean moving;

    PlayerState(Object player, double x, double y, double z,
                float yaw, float pitch, boolean onGround, int selectedSlot,
                int mainHandCount, int mainHandMaxStack, int offHandCount,
                float health, float absorption, float attackStrengthScale,
                boolean sprinting, boolean shifting, boolean moving) {
        this.player = player;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.selectedSlot = selectedSlot;
        this.mainHandCount = mainHandCount;
        this.mainHandMaxStack = mainHandMaxStack;
        this.offHandCount = offHandCount;
        this.health = health;
        this.absorption = absorption;
        this.attackStrengthScale = attackStrengthScale;
        this.sprinting = sprinting;
        this.shifting = shifting;
        this.moving = moving;
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

    public int selectedSlot() {
        return selectedSlot;
    }

    public int mainHandCount() {
        return mainHandCount;
    }

    public int mainHandMaxStack() {
        return mainHandMaxStack;
    }

    public int offHandCount() {
        return offHandCount;
    }

    public float health() {
        return health;
    }

    public float absorption() {
        return absorption;
    }

    /** 1.9+ 攻击冷却进度；没有冷却机制的旧版固定为 1。 */
    public float attackStrengthScale() {
        return attackStrengthScale;
    }

    public boolean sprinting() {
        return sprinting;
    }

    public boolean shifting() {
        return shifting;
    }

    /** 是否有水平移动输入，而不是被动击退产生的速度。 */
    public boolean moving() {
        return moving;
    }
}
