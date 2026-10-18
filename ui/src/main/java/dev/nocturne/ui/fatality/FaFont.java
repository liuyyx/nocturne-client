package dev.nocturne.ui.fatality;

import dev.nocturne.ui.render.Renderer;

/**
 * Fatality 界面看到的一份字体：只暴露度量与「按给定设备位置画一行字」。
 *
 * <p>度量单位是<b>设计单位</b>（{@code FaTheme} 里所有尺寸的单位），而不是像素：界面整体在
 * {@code FaDraw} 上按一个缩放因子绘制，同一份布局在任何分辨率下比例一致。绘制时把设备坐标
 * （后端逻辑像素）交给 {@link #draw}，字体自己按烘焙进本对象的换算把字形画成纹理四边形。
 *
 * <p>实现有两族（见 {@code dev.nocturne.ui.text}）：
 * <ul>
 *   <li>字形图集实现——Java2D 运行期栅格 + {@link Renderer#texturedQuad} 拼接，能复刻
 *       Fatality 的 Manrope/MiSans/Lucide 字形；</li>
 *   <li>游戏字体回落——后端不支持纹理（或 {@code java.desktop} 不可用）时改用
 *       {@link Renderer#text}/{@link Renderer#textWidth}，字形与上游不同但布局口径一致。</li>
 * </ul>
 */
public interface FaFont {

    /** @return 文本宽度（设计单位） */
    float width(String text);

    /** @return 行高（设计单位） */
    float height();

    /** @return 栅格化用的字号（后端逻辑像素）；回落实现返回 0 */
    int pxSize();

    /** @return 图集中的字体标识；回落实现返回 -1 */
    int fontId();

    /**
     * 在设备坐标处绘制一行字。
     *
     * @param renderer 绘制面
     * @param text     文本
     * @param deviceX  文本盒左边缘（后端逻辑像素）
     * @param deviceY  文本盒上边缘（后端逻辑像素）
     * @param argb     调制色（非预乘 {@code 0xAARRGGBB}）
     */
    void draw(Renderer renderer, String text, float deviceX, float deviceY, int argb);

    // ── 设计单位下的字号表；每个不同字号各自一张图集，故这组值保持精简 ──────────
    /** Logo 字号。 */
    float LOGO = 15.0f;
    /** 分类 Tab 字号。 */
    float TAB = 9.4f;
    /** 卡片标题字号。 */
    float TITLE = 10.0f;
    /** 参数行标签字号。 */
    float LABEL = 9.8f;
    /** 输入框 / 数值字号。 */
    float FIELD = 9.6f;
    /** 次要文字字号。 */
    float SMALL = 8.8f;

    /** 字体句柄集；界面按语义取字体，换字体实现只需换掉这个 Provider。 */
    interface Provider {
        FaFont logo();

        FaFont tab();

        FaFont title();

        FaFont label();

        FaFont field();

        FaFont small();

        FaFont icon(float size);
    }
}
