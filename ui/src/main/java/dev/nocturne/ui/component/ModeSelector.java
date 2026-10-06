package dev.nocturne.ui.component;

import dev.nocturne.client.value.ModeValue;
import dev.nocturne.ui.render.Renderer;
import dev.nocturne.ui.theme.Theme;

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
        String current = value.display();
        float currentWidth = renderer.textWidth(current, Theme.FONT_SIZE_SMALL);
        // P15：窄行时左右双文本重叠——左侧名按剩余宽度截断，右侧当前值优先完整显示。
        float nameX = x + Theme.ROW_CONTENT_INSET;
        float nameMax = Math.max(0f, width - 2f * Theme.ROW_CONTENT_INSET - currentWidth - 8f);
        String name = ellipsize(value.name(), nameMax, renderer);
        renderer.text(name, nameX, textY, Theme.FONT_SIZE_SMALL,
                Theme.TEXT_SECONDARY);
        renderer.text(current, x + width - currentWidth - Theme.ROW_CONTENT_INSET, textY,
                Theme.FONT_SIZE_SMALL, Theme.PRIMARY);
    }

    /** 按像素宽截断文本并加省略号；宽度不足以放省略号时返回空串。 */
    private static String ellipsize(String text, float maxWidth, Renderer renderer) {
        if (text == null) {
            return "";
        }
        if (renderer.textWidth(text, Theme.FONT_SIZE_SMALL) <= maxWidth) {
            return text;
        }
        String dots = "...";
        if (renderer.textWidth(dots, Theme.FONT_SIZE_SMALL) > maxWidth) {
            return "";
        }
        StringBuilder out = new StringBuilder(text);
        while (out.length() > 0
                && renderer.textWidth(out + dots, Theme.FONT_SIZE_SMALL) > maxWidth) {
            out.setLength(out.length() - 1);
        }
        return out + dots;
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
