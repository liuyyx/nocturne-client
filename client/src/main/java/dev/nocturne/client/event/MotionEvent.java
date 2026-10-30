package dev.nocturne.client.event;

/**
 * 本地玩家位置状态提交事件。
 *
 * <p>前沿的坐标、视角与落地状态可由订阅者修改；钩子会在原方法继续执行前把修改写回玩家。
 */
public final class MotionEvent {

    /** true=提交前，false=提交后。 */
    public final boolean pre;
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public boolean onGround;

    public MotionEvent(boolean pre, double x, double y, double z,
                       float yaw, float pitch, boolean onGround) {
        this.pre = pre;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
    }
}
