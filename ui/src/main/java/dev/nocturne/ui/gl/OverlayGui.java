package dev.nocturne.ui.gl;

import dev.nocturne.ui.render.Renderer;

import io.github.humbleui.skija.Canvas;

/**
 * 叠加层可驱动的界面契约。
 *
 * <p>存在两个实现，对应两套绘制方式：
 * <ul>
 *   <li>{@link dev.nocturne.ui.clickgui.ClickGui}——面向 {@link Renderer} 抽象（矩形 + 文字原语），
 *       任何后端都能画，但表达不出模糊、发光、图标字体等效果；</li>
 *   <li>{@link dev.nocturne.ui.skija.SetsunaClickGui}——直接用 Skija {@link Canvas} 绘制，
 *       能完整复刻移植来的视觉语言，仅在 Skija 后端可用时启用。</li>
 * </ul>
 *
 * <p>叠加层只依赖本接口做输入派发；渲染时把两条通道都传下去，由实现各自取用（见
 * {@link #render(Renderer, Canvas)}）。
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

    /** 复位全树交互态（关闭界面时调用，避免丢失的释放事件让拖动状态残留）。 */
    void cancelInteractions();

    /**
     * 渲染一帧。
     *
     * @param renderer 抽象绘制面（所有实现都可用）
     * @param canvas   Skija 画布；非 Skija 后端或本帧不可绘制时为 {@code null}。
     *                 需要 Canvas 的实现应在为 {@code null} 时跳过绘制。
     */
    void render(Renderer renderer, Canvas canvas);
}
