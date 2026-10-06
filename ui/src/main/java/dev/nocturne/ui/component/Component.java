package dev.nocturne.ui.component;

import dev.nocturne.ui.render.Renderer;

/**
 * 具备位置、可绘制、可交互的矩形基类，是所有 UI 控件的公共父类。
 *
 * <p>控件以树状组织：{@link Panel} 持有子控件并负责输入路由与命中测试。约定渲染自父到子、
 * 命中测试自顶（后添加者）向下，输入事件沿同一方向传递，事件返回 {@code true} 表示已消费。
 *
 * <p>坐标 {@code (x, y)} 为控件左上角，{@code width}/{@code height} 为尺寸，均以屏幕像素为单位；
 * 原点在窗口左上角，y 轴向下。
 */
public abstract class Component {

    /** 左上角 x 坐标（屏幕像素）。 */
    protected float x;
    /** 左上角 y 坐标（屏幕像素）。 */
    protected float y;
    /** 宽度（屏幕像素）。 */
    protected float width;
    /** 高度（屏幕像素）。 */
    protected float height;
    /** 是否可见；不可见时不绘制、不参与命中测试，默认可见。 */
    protected boolean visible = true;
    /** 指针是否悬停在本控件上，由 {@link #updateHover} 维护。 */
    protected boolean hovered;

    /** 返回左上角 x 坐标。 */
    public float x() {
        return x;
    }

    /** 返回左上角 y 坐标。 */
    public float y() {
        return y;
    }

    /** 返回宽度。 */
    public float width() {
        return width;
    }

    /** 返回高度。 */
    public float height() {
        return height;
    }

    /** 返回右边界 x 坐标（{@code x + width}）。 */
    public float right() {
        return x + width;
    }

    /** 返回下边界 y 坐标（{@code y + height}）。 */
    public float bottom() {
        return y + height;
    }

    /** 一次性设置位置与尺寸；子类通常在父容器分配布局时调用。 */
    public void setBounds(float x, float y, float width, float height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** 返回是否可见。 */
    public boolean isVisible() {
        return visible;
    }

    /** 设置可见性；不可见的控件不绘制、不接收输入。 */
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /** 返回指针是否悬停在本控件上。 */
    public boolean isHovered() {
        return hovered;
    }

    /** 判断点 {@code (mx, my)} 是否落在本控件矩形内（含边界）；零尺寸不命中任何点。 */
    public boolean contains(double mx, double my) {
        return width > 0f && height > 0f
                && mx >= x && mx <= x + width && my >= y && my <= y + height;
    }

    /** 更新悬停状态；返回 true 表示指针位于控件内。 */
    public boolean updateHover(double mx, double my) {
        // 不可见控件即便指针在内也不算悬停
        hovered = visible && contains(mx, my);
        return hovered;
    }

    /**
     * 推进基于时间的状态（动画、过渡）；每帧调用一次。
     *
     * <p>默认无操作。需要随帧推进的控件（动画类、容器）覆写本方法；容器覆写时应转发给子控件，
     * 使整棵子树共享同一个由外部注入的时钟，而不是各自读取 {@code System.currentTimeMillis}。
     *
     * @param nowMs 当前时间（毫秒），与 {@link dev.nocturne.ui.anim.Animation} 使用同一时间基准
     */
    public void update(long nowMs) {
    }

    /**
     * 取消本控件上尚未结束的交互手势（按下 / 拖拽），并把悬停状态复位。
     *
     * <p>事件丢失（GUI 关闭、容器被隐藏、叠加层提前返回）时，控件的 {@code dragging}/{@code pressed}
     * 等中间态不会被释放事件清除，会在下次打开时造成「未点击却改写数值、整列跳到错误坐标」。
     * 容器覆写时应递归到全部子控件，且<b>不</b>检查可见性——隐藏的子树同样需要复位。
     */
    public void cancelInteractions() {
        hovered = false;
    }

    /** 将本控件绘制到屏幕；实现方必须先检查 {@link #visible}，不可见时直接返回。 */
    public abstract void render(Renderer renderer);

    // -------------------------------------------------------------- input hooks
    // 返回 true 表示消费该事件，阻止继续传递给下层控件。默认实现一律不消费。

    /** 鼠标按下；默认不消费。{@code button} 为 GLFW 按键编号（0 为左键）。 */
    public boolean mouseClicked(double mx, double my, int button) {
        return false;
    }

    /** 鼠标释放；默认不消费。 */
    public boolean mouseReleased(double mx, double my, int button) {
        return false;
    }

    /** 鼠标拖拽；{@code dx}/{@code dy} 为相对上一帧的位移增量。默认不消费。 */
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return false;
    }

    /** 滚轮滚动；{@code amount} 为正表示向上滚动。默认不消费。 */
    public boolean mouseScrolled(double mx, double my, double amount) {
        return false;
    }

    /**
     * 键盘按下；{@code keyCode} 为 <b>AWT VK 码</b>（跨 LWJGL2 / GLFW 的统一语义，由输入后端负责翻译），
     * {@code modifiers} 为修饰键位掩码。默认不消费。
     */
    public boolean keyPressed(int keyCode, int modifiers) {
        return false;
    }

    /** 字符输入；用于文本编辑类控件。默认不消费。 */
    public boolean charTyped(char character) {
        return false;
    }
}
