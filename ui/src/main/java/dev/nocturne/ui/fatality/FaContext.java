package dev.nocturne.ui.fatality;

/** Fatality 界面所有控件共享的每帧状态。 */
final class FaContext {
    /** 所属界面，控件通过它开关浮层、取焦点、发提示。 */
    final FaClickGui gui;
    /** 本帧的绘制面；由界面每帧设置。 */
    FaDraw draw;
    /** 指针 x（设计单位）。 */
    float mouseX;
    /** 指针 y（设计单位）。 */
    float mouseY;
    /** 本帧时长（秒）。 */
    float dt;
    /** 指针悬在浮层上时为 false，避免其下的控件同时高亮。 */
    boolean hoverEnabled = true;

    FaContext(FaClickGui gui) {
        this.gui = gui;
    }

    /** @return 指针是否在给定矩形内；指针被浮层占用时恒为 false */
    boolean hovered(float x, float y, float w, float h) {
        return hoverEnabled && mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    /** 与 {@link #hovered} 同义，但不看 {@link #hoverEnabled}（给命中测试用）。 */
    static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
