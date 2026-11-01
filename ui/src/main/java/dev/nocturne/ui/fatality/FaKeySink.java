package dev.nocturne.ui.fatality;

/** 持有界面焦点时接收键盘输入的组件。 */
interface FaKeySink {

    /**
     * 处理一次按键。
     *
     * @param key       AWT VK 码
     * @param modifiers AWT 修饰位（SHIFT=1、CTRL=2、ALT=8）
     * @return 消费返回 {@code true}
     */
    boolean keyPressed(int key, int modifiers);

    /** @return 消费返回 {@code true} */
    default boolean charTyped(char character) {
        return false;
    }

    /** @return 当前是否正在等待用户按键；真正捕获时输入层必须派发完整键表，而不只是可打印键。 */
    default boolean capturing() {
        return false;
    }

    /** 焦点移走（点到别处、界面关闭）。 */
    default void focusLost() {
    }
}
