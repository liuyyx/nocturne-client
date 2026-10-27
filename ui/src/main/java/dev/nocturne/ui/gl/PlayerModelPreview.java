package dev.nocturne.ui.gl;

/**
 * 游戏原生的库存界面人物渲染入口，不允许拿头像、文字或人形图标冒充模型。
 * 坐标是当前 {@link UiBackend} 的逻辑像素，边界为左上/右下；调用必须发生在当前绘制帧内。
 */
public interface PlayerModelPreview {
    /**
     * 提交玩家模型；后端无法调用原生入口、玩家不存在或调用失败时返回 false。
     * 不得留下修改过的游戏渲染状态；extractor 路径仅提交给游戏自己的绘制队列。
     */
    boolean drawPlayer(Object player, float left, float top, float right, float bottom,
                       float mouseX, float mouseY);
}
