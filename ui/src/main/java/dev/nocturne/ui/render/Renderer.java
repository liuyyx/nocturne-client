package dev.nocturne.ui.render;

/**
 * UI 组件编写时所面向的绘制表面（抽象缝）。
 *
 * <p>实现有两类：游戏内的 OpenGL 后端（{@code GlRenderer} / {@code ModernRenderer}），
 * 以及测试用的无头记录器。把 UI 全部写在这层接口之上，
 * 就不需要 Minecraft 运行时也能跑通整棵组件树。
 */
public interface Renderer {


    /**
     * 绘制一个填充矩形。
     *
     * @param x      左上角 x（屏幕坐标）
     * @param y      左上角 y（屏幕坐标）
     * @param width  宽度；小于等于 0 时实现应跳过绘制
     * @param height 高度；小于等于 0 时实现应跳过绘制
     * @param color  填充色；为 {@code null} 时应跳过绘制
     */
    void rect(float x, float y, float width, float height, Color color);


    /**
     * 绘制一个圆角矩形。
     *
     * @param x 左上角 x（屏幕坐标）
     * @param y 左上角 y（屏幕坐标）
     * @param width 宽度；小于等于 0 时实现应跳过绘制
     * @param height 高度；小于等于 0 时实现应跳过绘制
     * @param radius 圆角半径，实现应自行夹取到不超过宽高的一半；小于等于 0.5 时退化为普通矩形
     * @param color 填充色；为 {@code null} 时应跳过绘制
     */
    void roundedRect(float x, float y, float width, float height, float radius, Color color);


    /**
     * 绘制矩形描边。
     *
     * @param x 左上角 x（屏幕坐标）
     * @param y 左上角 y（屏幕坐标）
     * @param width 宽度；小于等于 0 时实现应跳过绘制
     * @param height 高度；小于等于 0 时实现应跳过绘制
     * @param lineWidth 线宽（像素）
     * @param color 描边色；为 {@code null} 时应跳过绘制
     */
    void outline(float x, float y, float width, float height, float lineWidth, Color color);


    /**
     * 绘制一行文本。
     *
     * @param text 文本内容；为 {@code null} 或空串时应跳过绘制
     * @param x 左上角 x（屏幕坐标）
     * @param y 左上角 y（屏幕坐标）
     * @param size 字号（像素高度）
     * @param color 填充色；为 {@code null} 时应跳过绘制
     */
    void text(String text, float x, float y, float size, Color color);


    /** @return 文本在给定字号下的绘制宽度（像素） */
    float textWidth(String text, float size);


    /** @return 给定字号下的行高（像素） */
    float textHeight(float size);

    /**
     * 用四个角的颜色画一个**逐顶点插值**的四边形（Gouraud）。
     *
     * <p>颜色选择器、渐变背景这类真正需要平滑过渡的地方用它：把渐变切成若干平涂小矩形来近似，
     * 会在接缝处露出色带（1.8.9 真机的水平条纹就是这个原因）。
     *
     * <p>默认实现返回 {@code false}，表示本后端没有逐顶点色——调用方应退回分段近似。
     *
     * @param x           左上角 x（屏幕坐标）
     * @param y           左上角 y（屏幕坐标）
     * @param width       宽度；小于等于 0 时实现应跳过绘制
     * @param height      高度；小于等于 0 时实现应跳过绘制
     * @param topLeft     左上角颜色
     * @param topRight    右上角颜色
     * @param bottomRight 右下角颜色
     * @param bottomLeft  左下角颜色
     * @return 已绘制返回 {@code true}；本后端不支持时返回 {@code false}（未做任何绘制）
     */
    default boolean gradientQuad(float x, float y, float width, float height, Color topLeft,
                                Color topRight, Color bottomRight, Color bottomLeft) {
        return false;
    }

    /**
     * 只更新纹理的一个子矩形（图集新增字形时用，避免每加一个字就重传整张 4 MB 图）。
     *
     * <p>默认实现退回 {@link #uploadTexture}（整张上传），因此所有后端都保持正确；
     * 支持 {@code glTexSubImage2D} 的后端应覆盖它。
     *
     * @param texture       已有纹理句柄
     * @param width         整张纹理宽
     * @param height        整张纹理高
     * @param argb          整张纹理的像素（{@code 0xAARRGGBB}，按行）
     * @param x             子矩形左上角 x
     * @param y             子矩形左上角 y
     * @param regionWidth   子矩形宽
     * @param regionHeight  子矩形高
     * @return 纹理句柄；失败时返回 0
     */
    default int uploadTextureRegion(int texture, int width, int height, int[] argb,
                                    int x, int y, int regionWidth, int regionHeight) {
        return uploadTexture(texture, width, height, argb);
    }

    /** 将随后的绘制裁剪到给定矩形，直到 {@link #popClip()}；支持嵌套。 */
    void pushClip(float x, float y, float width, float height);

    /** 弹出最近一次 {@link #pushClip} 设置的裁剪区域；栈空时为空操作。 */
    void popClip();

    /**
     * 上传或更新一张纹理。
     *
     * <p>像素格式固定为 {@code 0xAARRGGBB}、行优先、左上角为原点，且<b>非预乘</b>：
     * 三个 GL 路径都用 {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} 混合，预乘会双重乘 alpha。
     *
     * @param id     已有纹理句柄；小于等于 0 表示新建
     * @param width  宽度（像素）；小于等于 0 时返回 {@code id} 原样
     * @param height 高度（像素）；小于等于 0 时返回 {@code id} 原样
     * @param argb   像素数据，长度必须不小于 {@code width * height}
     * @return 纹理句柄；失败或本后端不支持纹理时返回 {@code id} 原样
     */
    int uploadTexture(int id, int width, int height, int[] argb);

    /**
     * 删除纹理。
     *
     * @param id 纹理句柄；小于等于 0 时忽略
     */
    void deleteTexture(int id);

    /**
     * 绘制一张纹理；支持调制时以 {@code argb} 乘上纹理颜色，否则参见 {@link #supportsTextureTint()}。
     *
     * <p>{@code uv} 为 0..1 的归一化纹理坐标；{@code u0/v0} 是左上角、{@code u1/v1} 是右下角。
     * {@code texture} 小于等于 0、宽高小于等于 0 或 {@code argb} 的 alpha 为 0 时跳过绘制。
     *
     * @param texture 纹理句柄
     * @param argb    调制色（{@code 0xFFFFFFFF} 表示原样绘制）
     */
    void texturedQuad(int texture, float x, float y, float width, float height,
                      float u0, float v0, float u1, float v1, int argb);

    /**
     * 纹理绘制入口是否能够应用 {@code argb} 调制。
     *
     * <p>少数游戏绘制入口没有调色参数；调用方可将颜色和透明度烘入纹理，再用白色绘制。
     *
     * @return 支持调制返回 {@code true}
     */
    default boolean supportsTextureTint() {
        return true;
    }

    /**
     * 本后端是否支持纹理栅格。
     *
     * <p>返回 {@code false} 时调用方必须走降级路径（例如用游戏字体代替字形图集），
     * 而不是继续调用 {@link #uploadTexture}/{@link #texturedQuad} 期待效果。
     *
     * @return 支持返回 {@code true}
     */
    default boolean supportsTextures() {
        return false;
    }
}
