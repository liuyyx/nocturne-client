package dev.nocturne.ui.fatality;

/** 参与内容区瀑布流布局的任何卡片：模块卡片与 Render 分类的预览卡片。 */
abstract class FaBox {

    /** @return 稳定高度（设计单位），用于列高平衡 */
    abstract float targetHeight();

    /**
     * 记录目标位置。
     *
     * @param snap 为 true 时立即吸附，不播动画（列数/列宽变化时用）
     */
    abstract void place(float targetX, float targetY, boolean snap);

    /** @return 目标 y（设计单位），用于侧边栏与滚动定位 */
    abstract float targetY();

    /**
     * 绘制。
     *
     * @param originX   内容原点 x 减去滚动
     * @param originY   内容原点 y 减去滚动
     * @param viewTop   可见带上沿；完全在外面的卡片跳过绘制但仍要推进动画
     * @param viewBottom 可见带下沿
     * @param alpha     父级透明度
     */
    abstract void render(FaContext ctx, float originX, float originY, float width, float viewTop,
                         float viewBottom, float alpha);

    abstract boolean mouseClicked(FaContext ctx, double mx, double my, int button);

    abstract void mouseDragged(FaContext ctx, double mx, double my);

    abstract void mouseReleased();

    abstract boolean mouseScrolled(FaContext ctx, double mx, double my, double amount);
}
