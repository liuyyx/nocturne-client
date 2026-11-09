package dev.nocturne.client.render3d;

/**
 * 世界空间 3D 覆盖层：模块实现本接口后，在游戏自己的世界绘制阶段被回调一次。
 *
 * <p>与 2D 的 {@code WorldOverlay} 的区别：
 * <ul>
 *   <li>绘制发生在游戏<b>世界渲染通道内</b>（相机变换、深度缓冲都已就绪），不是 HUD 之后；</li>
 *   <li>坐标是绝对世界坐标，深度测试由后端统一处理，不存在 2D 投影的遮挡问题；</li>
 *   <li>只有后端真实可用（1.8.9 固定管线）才会被调用；版本不支持时能力表里显式登记原因。</li>
 * </ul>
 *
 * <p>注册方式与 {@code WorldOverlay} 一致：模块启用后由渲染器自动发现，不需要额外注册调用。
 * 实现不得抛异常——单模块异常会被隔离并限流记录，但那一帧不会重试。
 */
public interface World3DOverlay {

    /**
     * 画一帧世界空间几何。
     *
     * @param context 当帧上下文（相机 / 视口 / 批次）
     */
    void drawWorld3D(World3DContext context);
}
