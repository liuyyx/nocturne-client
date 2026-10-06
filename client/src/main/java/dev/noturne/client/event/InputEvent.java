package dev.noturne.client.event;

/**
 * 输入事件：按键/鼠标按下沿到来时投递。
 *
 * <p>当前仅立类型：1.8.9/1.16.5 的输入现状是叠加层每帧轮询（{@code ReflectiveInput}），
 * 事件化投递要等 P5 的回调链路补齐。Clicker 类模块（LeftClicker/RightClicker）的
 * 连击判断挂在这里，投递点接入前它们仍可用轮询值自行判断。
 */
public final class InputEvent {

    /** 输入类型。 */
    public enum Kind {
        /** 键盘按下（值为 AWT VK 码）。 */
        KEY_PRESS,
        /** 鼠标按下（值为按钮编号，0 左键/1 右键）。 */
        MOUSE_PRESS
    }

    /** 输入类型。 */
    public final Kind kind;
    /** 键码或按钮编号（见 {@link Kind}）。 */
    public final int code;

    public InputEvent(Kind kind, int code) {
        this.kind = kind;
        this.code = code;
    }
}
