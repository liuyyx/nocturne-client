package dev.nocturne.client.render;

/** 游戏实体渲染期间可用的后端状态操作；不支持的后端逐项降级。 */
public interface RenderCapabilities {
    void depthTest(boolean enabled);
    void texture2d(boolean enabled);
    void color(int argb);
    void polygonOffset(boolean enabled);
    int[] frameSize();
    boolean readPixels(int width, int height, int[] out);
}
