package dev.nocturne.client.input;

/**
 * 鼠标按钮编码归一：SDL/Minecraft 的 {@code MouseButtonInfo#button()} → 客户端契约。
 *
 * <p>契约（{@code MouseButtonEvent}）：{@code 0=左键、1=右键、2=中键}。
 * SDL 的原生编号是 {@code SDL_BUTTON_LEFT=1、MIDDLE=2、RIGHT=3}——26.3 的
 * {@code MouseHandler#onButton} 直接把 SDL 编号塞进 {@code MouseButtonInfo}。
 * 不归一的话，左键会被模块当成右键（RightClicker 点左键也自动右键，真机可复现）。
 *
 * <p>GLFW 世代（{@code MouseHandler#onPress}）本来就是 0=左/1=右/2=中，<b>不得</b>经过本类。
 */
public final class MouseButtons {

    /** SDL 左键。 */
    private static final int SDL_LEFT = 1;
    /** SDL 中键。 */
    private static final int SDL_MIDDLE = 2;
    /** SDL 右键。 */
    private static final int SDL_RIGHT = 3;

    private MouseButtons() {
    }

    /**
     * 把 SDL 编号归一为客户端契约编号。
     *
     * <p>未收录的按钮（侧键 X1/X2 等）原样返回：契约只定义了三键，猜测映射比不识别更糟。
     *
     * @param sdl SDL 按钮编号
     * @return 契约编号（1→0、3→1、2→2；其它原样）
     */
    public static int fromSdl(int sdl) {
        switch (sdl) {
            case SDL_LEFT:
                return 0;
            case SDL_RIGHT:
                return 1;
            case SDL_MIDDLE:
                return 2;
            default:
                return sdl;
        }
    }
}
