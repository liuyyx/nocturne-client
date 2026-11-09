package dev.nocturne.client.render;

/** 游戏实体渲染期间可用的后端状态操作；不支持的后端逐项降级。 */
public interface RenderCapabilities {
    void depthTest(boolean enabled);

    /**
     * 读取深度测试当前是否开启，供调用方在修改前快照、修改后按原值还原。
     *
     * <p>返回 {@code null} 表示后端无法查询——此时调用方<b>不得</b>修改深度状态：
     * 不知道原值就只能猜，猜错会把整个世界渲染成穿透/被 HUD 遮挡。
     *
     * @return 当前深度测试状态；后端不支持查询时为 {@code null}
     */
    default Boolean depthTestEnabled() {
        return null;
    }

    void texture2d(boolean enabled);
    void color(int argb);
    void polygonOffset(boolean enabled);
    int[] frameSize();
    boolean readPixels(int width, int height, int[] out);
}
