package dev.nocturne.client.render3d;

/**
 * 世界空间 3D 批次的绘制面：模块在 {@link World3DOverlay#drawWorld3D(World3DContext)} 里调用。
 *
 * <p>坐标为<b>绝对世界坐标</b>（与方块坐标同一套，例如方块 {@code (x,y,z)} 的立方体是
 * {@code [x, x+1] × [y, y+1] × [z, z+1]}）。后端自己负责相机变换（投影 + 模型视图），模块不需要
 * 减去相机眼位，也不需要关心当前 GL 矩阵。
 *
 * <p>选项是<b>粘滞</b>的：{@link #color(int)} / {@link #lineWidth(float)} / {@link #depthTest(boolean)}
 * 等设置会一直影响后续图元，直到下一次显式修改。每帧开始时后端把选项复位为默认值
 * （深度测试开、背面剔除关、混合关、线宽 1.5、颜色不透明白）。
 *
 * <p>实现只允许在渲染线程、{@code begin()} 与 {@code end()} 之间调用；绘制端不得缓存批次对象。
 */
public interface World3DBatch {

    /**
     * 设置后续图元的颜色。
     *
     * @param argb 0xAARRGGBB；只有 {@link #blend(boolean)} 打开时 alpha 才参与混合
     * @return 本批次（便于链式调用）
     */
    World3DBatch color(int argb);

    /**
     * 设置后续线段的宽度（像素）。
     *
     * @param width 线宽；驱动可能把大于 1 的值夹到 1，调用方不应依赖精确值
     * @return 本批次
     */
    World3DBatch lineWidth(float width);

    /**
     * 是否启用深度测试（默认开）。
     *
     * @param enabled {@code true} 时被世界几何遮挡的图元不可见
     * @return 本批次
     */
    World3DBatch depthTest(boolean enabled);

    /**
     * 是否启用背面剔除（默认关）。四边形/环的绕序由调用方负责。
     *
     * @param enabled 是否按 {@code GL_CCW} 正面剔除背面
     * @return 本批次
     */
    World3DBatch cull(boolean enabled);

    /**
     * 是否启用 alpha 混合（默认关，即不透明白）。
     *
     * @param enabled 打开后按 {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} 混合
     * @return 本批次
     */
    World3DBatch blend(boolean enabled);

    /** 画一条世界空间线段。 */
    void line(double x1, double y1, double z1, double x2, double y2, double z2);

    /** 画一个轴对齐立方体的 12 条边（线框）。 */
    void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

    /**
     * 画一个水平圆环（XZ 平面，线环 {@code GL_LINE_LOOP}）。
     *
     * @param centerX 圆心 X
     * @param centerY 圆心 Y
     * @param centerZ 圆心 Z
     * @param radius  半径
     * @param segments 分段数（至少 3；越大越圆）
     */
    void ring(double centerX, double centerY, double centerZ, double radius, int segments);

    /**
     * 提交一段 {@code GL_TRIANGLE_STRIP}。
     *
     * @param xyz         扁平顶点数组 {@code x0,y0,z0,x1,y1,z1,…}；不足的部分忽略
     * @param vertexCount 顶点数（不得超过 {@code xyz.length / 3}）
     */
    void triangleStrip(float[] xyz, int vertexCount);

    /** 结束当前批次（立即模式实现为空操作；缓冲实现会在这里提交）。 */
    void flush();
}
