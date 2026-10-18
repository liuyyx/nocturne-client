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

    /** 焦点移走（点到别处、界面关闭）。 */
    default void focusLost() {
    }
}
