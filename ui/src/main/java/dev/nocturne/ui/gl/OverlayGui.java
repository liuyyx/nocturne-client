package dev.nocturne.ui.gl;

import dev.nocturne.ui.render.Renderer;

/**
 * 叠加层可驱动的界面契约。
 *
 * <p>当前实现是 {@link dev.nocturne.ui.fatality.FaClickGui}——移植自 FatalityClickGui 的
 * 三列瀑布流界面。它完全建立在 {@link Renderer} 抽象（矩形 / 圆角 / 描边 / 纹理 / 文字原语）
 * 之上，因此三个后端（{@code gl-fixed} / {@code gl-core} / {@code gui-extractor}）都能画。
 *
 * <p>叠加层只依赖本接口做输入派发，绘制时把 {@link Renderer} 传下去（见 {@link #render(Renderer)}）。
 */
public interface OverlayGui {

    /** @return 界面当前是否打开；关闭时不绘制也不消费输入 */
    boolean isOpen();

    /** 直接设置开关状态。 */
    void setOpen(boolean open);

    /** 在打开与关闭之间切换。 */
    void toggle();

    /**
     * 同步绘制区域尺寸，供滚动范围、命中测试与布局计算使用。
     *
     * @param width  绘制区域宽度（像素）；0 表示未知
     * @param height 绘制区域高度（像素）；0 表示未知
     */
    void setViewport(int width, int height);

    /** 推进每帧状态（悬停、动画）；每帧调用一次。 */
    void update(long nowMs, double mouseX, double mouseY);

    boolean mouseClicked(double mx, double my, int button);

    boolean mouseReleased(double mx, double my, int button);

    boolean mouseDragged(double mx, double my, int button, double dx, double dy);

    boolean mouseScrolled(double mx, double my, double amount);

    /** @param keyCode AWT VK 码 */
    boolean keyPressed(int keyCode, int modifiers);

    /**
     * 派发一个已输入的字符。
     *
     * <p>键盘事件只给出键码，拿不到"按了哪个字符"（要结合键盘布局与 Shift），所以文本框必须靠
     * 本入口拿到真实字符。派发源（{@link GuiOverlay}）在轮询模型下没有字符事件，只有
     * {@link dev.nocturne.ui.gl.OverlayGui} 的实现自己按需从输入源补充。
     *
     * @param c 输入字符
     * @return 消费返回 {@code true}
     */
    boolean charTyped(char c);

    /** 复位全树交互态（关闭界面时调用，避免丢失的释放事件让拖动状态残留）。 */
    void cancelInteractions();

    /**
     * 渲染一帧。
     *
     * @param renderer 绘制面（所有后端都实现它）
     */
    void render(Renderer renderer);
}
