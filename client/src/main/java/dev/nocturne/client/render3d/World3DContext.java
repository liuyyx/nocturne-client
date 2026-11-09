package dev.nocturne.client.render3d;

/**
 * 一次世界空间 3D 绘制回调的上下文：相机、视口与批次绘制面。
 *
 * <p>只在 {@link World3DOverlay#drawWorld3D(World3DContext)} 与 {@code WorldRenderEvent} 的订阅者
 * 回调期间有效，不得跨帧保存。<b>不要</b>在回调外调用 {@link #batch()}：那一帧的 GL 状态已经
 * 还原，图元会落到错误的位置或直接丢失。
 */
public interface World3DContext {

    /** @return 当帧相机快照（复用实例，只在回调内读取） */
    WorldCamera camera();

    /**
     * @return 当帧批次绘制面；后端没有可用批次时返回空实现（所有方法都是空操作），绝不返回
     *         {@code null}——模块代码不需要额外判空
     */
    World3DBatch batch();

    /** @return 帧缓冲宽度（像素）；后端未就绪时为 0 */
    int viewportWidth();

    /** @return 帧缓冲高度（像素）；后端未就绪时为 0 */
    int viewportHeight();
}
