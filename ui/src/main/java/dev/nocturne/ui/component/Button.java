package dev.nocturne.ui.component;

import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;
import dev.nocturne.ui.theme.Theme;

/**
 * 带文本标签的按钮：左键点击其矩形区域即触发一次动作。
 *
 * <p>动作在按下（{@code mouseClicked}）时立即执行，无按下/抬起状态机；
 * 表面为次要色容器，悬停时向次要色偏移 12%。
 */
public class Button extends Component {

    /** 按钮显示文本，居中绘制。 */
    private final String label;
    /** 点击回调；可为 null（表示无操作）。 */
    private final Runnable action;

    /**
     * @param label  按钮文本
     * @param action 左键点击时执行的回调，允许为 null
     */
    public Button(String label, Runnable action) {
        this.label = label;
        this.action = action;
    }

    /** 返回按钮文本。 */
    public String label() {
        return label;
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        Renderer r = renderer;
        Color surface = hovered
                ? Theme.SECONDARY_CONTAINER.mix(Theme.SECONDARY, 0.12f)
                : Theme.SECONDARY_CONTAINER;
        r.roundedRect(x, y, width, height, Theme.CONTROL_RADIUS, surface);
        // 文本按控件区域水平、垂直居中
        float textX = x + (width - r.textWidth(label, Theme.FONT_SIZE_SMALL)) / 2f;
        float textY = y + (height - r.textHeight(Theme.FONT_SIZE_SMALL)) / 2f;
        r.text(label, textX, textY, Theme.FONT_SIZE_SMALL, Theme.ON_SECONDARY_CONTAINER);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        if (action != null) {
            action.run();
        }
        return true;
    }
}
