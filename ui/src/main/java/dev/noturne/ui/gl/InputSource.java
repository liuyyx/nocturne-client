package dev.noturne.ui.gl;

/**
 * GUI 的输入来源：把「当前指针在哪、哪些键/键位被按住」这一组瞬时状态暴露给 {@link GuiOverlay}。
 *
 * <p>为什么不 hook 游戏的回调，而是每帧轮询？游戏跨度太大——1.12 及更早走 LWJGL2 的
 * {@code Mouse}/{@code Keyboard}，1.13+ 走 GLFW，两代的回调签名、事件对象乃至「事件在哪个线程派发」
 * 都不相同。轮询把这份差异关在实现类内部：只要每帧问一次，就不需要理解任何一方的回调模型，
 * 也不会因为回调签名不匹配而让整个 GUI 失效。
 *
 * <p>坐标契约：{@link #mouseX()} / {@link #mouseY()} 返回<b>GUI 坐标系</b>下的位置——原点在绘制区域
 * 左上角、y 轴向下、单位与 {@link UiBackend} 的绘制坐标一致。实现类负责把窗口坐标、高 DPI 缩放和
 * y 轴方向统一换算到这里，调用方不需要知道窗口尺寸。
 */
public interface InputSource {

    /** @return 指针在 GUI 坐标系下的 x（像素） */
    double mouseX();

    /** @return 指针在 GUI 坐标系下的 y（像素），向下为正 */
    double mouseY();

    /**
     * 查询鼠标按键是否按住。
     *
     * @param button 按键编号，0 为左键、1 为右键（与 GLFW 一致）
     * @return 按住返回 true
     */
    boolean mouseDown(int button);

    /**
     * 取出并清空自上次调用以来累积的滚轮增量。
     *
     * <p>是「取出」而非「读取」：GLFW 只提供回调、不提供查询，增量必须先攒起来；
     * 若不清空，同一格滚动会在后续每一帧被重复消费。
     *
     * @return 滚轮增量，正数表示向上滚动；无输入时为 0
     */
    double scrollDelta();

    /**
     * 查询某个键是否按住。
     *
     * @param key 键码，与 {@link GuiOverlay#KEY_RIGHT_SHIFT} 同源（GLFW 键码，LWJGL2 亦兼容）
     * @return 按住返回 true
     */
    boolean keyDown(int key);

    /**
     * 设置指针是否被游戏锁定。
     *
     * <p>游戏中指针默认被捕获并隐藏（移动鼠标即转动视角），此时 {@link #mouseX()} 恒为中心点、
     * GUI 完全无法操作。打开 GUI 必须解除捕获，关闭时恢复，否则会破坏正常游戏操作。
     *
     * @param grabbed true 表示交还给游戏（锁定），false 表示释放给 GUI 使用
     */
    void setPointerGrabbed(boolean grabbed);

    /** @return 实际生效的输入后端名称，用于日志诊断，如 {@code "lwjgl2"} / {@code "glfw"} / {@code "none"} */
    String describe();
}
