package dev.nocturne.ui.render;

/**
 * 绘制计划的最小执行面：**只有三个原语**。
 *
 * <p>所有版本相关的知识都收敛到本接口的三种实现（1.8.9 固定管线 / 1.13–1.19
 * {@code RenderSystem} / 1.20+ {@code GuiGraphics} 与 26.x extractor）。上层
 * （{@link DrawPlan}）不认识圆角、渐变、阴影——那些在计划里就已经降级成 {@link #fill}。
 *
 * <p>坐标是屏幕坐标（左上原点，y 向下），与 {@link Renderer} 一致。
 */
public interface DrawSink {

    /**
     * 填充一个矩形。
     *
     * @param x1    左边界
     * @param y1    上边界
     * @param x2    右边界（不含）
     * @param y2    下边界（不含）
     * @param color 填充色；调用方保证非空且不透明
     */
    void fill(float x1, float y1, float x2, float y2, Color color);

    /**
     * 绘制一块纹理。
     *
     * @param texture 纹理句柄（由 {@link Renderer#texturedQuad} 的同一编号空间给出）
     * @param x       左上角 x
     * @param y       左上角 y
     * @param width   宽度
     * @param height  高度
     * @param u0      左纹理坐标（0–1）
     * @param v0      上纹理坐标（0–1）
     * @param u1      右纹理坐标（0–1）
     * @param v1      下纹理坐标（0–1）
     * @param tint    叠加色；调用方保证非空
     */
    void blit(int texture, float x, float y, float width, float height,
              float u0, float v0, float u1, float v1, Color tint);

    /**
     * 把后续绘制裁剪到给定矩形，直到下一次 {@code scissor}。
     *
     * <p>参数是**已经与父裁剪求过交集**的绝对矩形；实现不需要自己再维护栈。
     *
     * @param x      左边界
     * @param y      上边界
     * @param width  宽度
     * @param height 高度
     */
    void scissor(float x, float y, float width, float height);
}
