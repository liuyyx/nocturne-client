package dev.nocturne.client.render3d;

/**
 * 世界空间 3D 渲染事件：游戏世界绘制阶段投递一次，载荷为相机、视口与批次绘制面。
 *
 * <p>与 2D 的 {@code RenderEvent} 分开：那个事件在帧交换回调里投递，只适合「每帧做点什么」，
 * 拿不到世界空间的 GL 状态。本事件只有在真实世界钩子织入且后端可用时才投递。
 *
 * <p>实例每帧复用（热路径不分配）：订阅者只能在回调内读取字段，不得跨帧保存引用。
 */
public final class WorldRenderEvent {

    /** 当帧上下文；{@code null} 表示只有相机数据、没有可绘制批次（正常投递时非 null）。 */
    public World3DContext context;

    /** 当帧渲染插值量（0–1）。 */
    public float partialTicks;
    /** 相机眼位（绝对世界坐标）。 */
    public double cameraX;
    public double cameraY;
    public double cameraZ;
    /** 相机朝向（度）。 */
    public float yaw;
    public float pitch;
    /** 有效竖直 FOV（度）。 */
    public float fov;
    /** 帧缓冲尺寸（像素）。 */
    public int viewportWidth;
    public int viewportHeight;

    /** 供复用实例改写载荷。 */
    public void update(WorldCamera camera, World3DContext context, int viewportWidth,
                       int viewportHeight) {
        this.context = context;
        if (camera == null) {
            this.partialTicks = 0f;
            this.cameraX = 0d;
            this.cameraY = 0d;
            this.cameraZ = 0d;
            this.yaw = 0f;
            this.pitch = 0f;
            this.fov = 0f;
        } else {
            this.partialTicks = camera.partialTicks();
            this.cameraX = camera.eyeX();
            this.cameraY = camera.eyeY();
            this.cameraZ = camera.eyeZ();
            this.yaw = camera.yaw();
            this.pitch = camera.pitch();
            this.fov = camera.fov();
        }
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }
}
