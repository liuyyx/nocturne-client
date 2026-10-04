package dev.noturne.ui.component;

import dev.noturne.client.value.ModeValue;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

/**
 * 枚举模式选择器：点击循环切换 {@link ModeValue} 的候选项，左侧显示设置项名称、右侧显示当前值。
 */
public final class ModeSelector extends Component {

    private final ModeValue value;

    public ModeSelector(ModeValue value) {
        this.value = value;
    }

    public ModeValue value() {
        return value;
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        // 设置行表面用低透明度的低级容器色；悬停时叠一层强调色状态层
        renderer.roundedRect(x, y, width, height, Theme.CONTROL_RADIUS,
                Theme.SURFACE_CONTAINER_LOW.withAlpha(160));
        if (hovered) {
            renderer.roundedRect(x, y, width, height, Theme.CONTROL_RADIUS,
                    Theme.PRIMARY.withAlpha(Theme.STATE_LAYER_ALPHA));
        }

        float textY = y + (height - renderer.textHeight(Theme.FONT_SIZE_SMALL)) / 2f;
        String name = value.name();
        renderer.text(name, x + Theme.ROW_CONTENT_INSET, textY, Theme.FONT_SIZE_SMALL,
                Theme.TEXT_SECONDARY);

        String current = value.display();
        float currentWidth = renderer.textWidth(current, Theme.FONT_SIZE_SMALL);
        renderer.text(current, x + width - currentWidth - Theme.ROW_CONTENT_INSET, textY,
                Theme.FONT_SIZE_SMALL, Theme.PRIMARY);
    }
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        value.next();
        return true;
    }
}
