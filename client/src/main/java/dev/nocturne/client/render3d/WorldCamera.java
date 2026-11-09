package dev.nocturne.client.render3d;

/**
 * 世界空间 3D 通道的相机快照（眼位 / 朝向 / 有效 FOV / 当帧插值量）。
 *
 * <p>实例由 {@code World3DRenderer} 持有并<b>每帧复用</b>：订阅者只能在回调内读取，不得跨帧保存
 * 引用。这跟 {@code RenderEvent} 的约定一致——热路径上不允许每帧 new 一个相机对象。
 *
 * <p>坐标约定与游戏一致：{@code yaw} 0 = 朝南（+Z），增大即向西转（90 = 朝西 / -X）；
 * {@code pitch} 正 = 向下看。视口是<b>帧缓冲像素</b>（不是 GUI 逻辑像素），由后端在
 * {@code glGetIntegerv(GL_VIEWPORT)} 里读回。
 */
public final class WorldCamera {

    /** 眼位（绝对世界坐标）。 */
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    /** 相机 yaw（度）。 */
    private float yaw;
    /** 相机 pitch（度，正 = 向下）。 */
    private float pitch;
    /** 有效竖直 FOV（度），已含疾跑/水下等修正。 */
    private float fov;
    /** 当帧渲染插值量（0–1）。 */
    private float partialTicks;
    /** 是否有一份可用快照；{@code false} 时所有 getter 返回 0。 */
    private boolean valid;

    /** 写入一份新快照（仅供 {@code CameraState} 调用）。 */
    public void set(double eyeX, double eyeY, double eyeZ,
                    float yaw, float pitch, float fov, float partialTicks) {
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.yaw = yaw;
        this.pitch = pitch;
        this.fov = fov;
        this.partialTicks = partialTicks;
        this.valid = true;
    }

    /** 把快照标记为失效（相机读不到时调用，避免上帧的旧值继续被绘制）。 */
    public void clear() {
        valid = false;
    }

    /** @return 是否有一份可用快照 */
    public boolean valid() {
        return valid;
    }

    public double eyeX() {
        return eyeX;
    }

    public double eyeY() {
        return eyeY;
    }

    public double eyeZ() {
        return eyeZ;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public float fov() {
        return fov;
    }

    public float partialTicks() {
        return partialTicks;
    }
}
