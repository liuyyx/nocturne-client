package dev.nocturne.client.render;

/**
 * 世界覆盖层：模块实现本接口后，叠加层每帧在**绘制通道内**回调它一次。
 *
 * <p>为什么不在事件总线里画：{@code RenderEvent} 的投递点在帧回调里，那时绘制后端还没
 * {@code beginFrame}（甚至还没挑到绘制上下文），画下去只会落到空处。因此绘制回调由
 * {@code GuiOverlay.drawFrame} 在 {@code beginFrame()} 之后、{@code endFrame()} 之前直接发起——
 * 与 HUD、ClickGUI 同一帧、同一后端，四个后端都能画。
 *
 * <p>实现要点：
 * <ul>
 *   <li>只在 {@code isEnabled()} 为真时被调用，不需要自己判开关；</li>
 *   <li>投影已经按当帧相机状态刷新过，直接用 {@link WorldProjection#project} 即可；</li>
 *   <li>不得抛异常——异常会被叠加层的隔离层吞掉并限流记录，但那一帧之后的覆盖物就没了。</li>
 * </ul>
 */
public interface WorldOverlay {

    /**
     * 画一帧世界覆盖物。
     *
     * @param draw       当帧绘制面（屏幕逻辑像素）
     * @param projection 当帧投影（相机眼位 / 朝向 / FOV / 视口都已就绪）
     */
    void drawWorldOverlay(OverlayDraw draw, WorldProjection projection);
}
