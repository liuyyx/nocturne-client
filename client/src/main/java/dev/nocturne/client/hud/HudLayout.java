package dev.nocturne.client.hud;

/** 九锚点布局纯函数；同锚点元素竖向堆叠，边缘保留 6px、元素间隔 4px。 */
public final class HudLayout {
    public static final float MARGIN = 6f;
    public static final float GAP = 4f;

    private HudLayout() {
    }

    /** 计算元素水平位置，并保证整个元素尽可能留在视口内。 */
    public static float x(HudAnchor anchor, float viewportWidth, float elementWidth) {
        float x;
        switch (anchor) {
            case TOP_CENTER:
            case CENTER:
            case BOTTOM_CENTER:
                x = (viewportWidth - elementWidth) * 0.5f;
                break;
            case TOP_RIGHT:
            case CENTER_RIGHT:
            case BOTTOM_RIGHT:
                x = viewportWidth - MARGIN - elementWidth;
                break;
            default:
                x = MARGIN;
                break;
        }
        return clamp(x, viewportWidth, elementWidth);
    }

    /**
     * 计算元素竖向位置。
     *
     * @param contentHeight 同锚点全部元素及间距的总高度
     * @param offset        本元素沿堆叠方向的累计偏移；底部锚点向上堆叠
     */
    public static float y(HudAnchor anchor, float viewportHeight, float contentHeight,
                          float offset, float elementHeight) {
        float top;
        switch (anchor) {
            case CENTER_LEFT:
            case CENTER:
            case CENTER_RIGHT:
                top = (viewportHeight - contentHeight) * 0.5f;
                break;
            case BOTTOM_LEFT:
            case BOTTOM_CENTER:
            case BOTTOM_RIGHT:
                return clamp(viewportHeight - MARGIN - elementHeight - offset,
                        viewportHeight, elementHeight);
            default:
                top = MARGIN;
                break;
        }
        return clamp(top + offset, viewportHeight, elementHeight);
    }


    private static float clamp(float value, float viewport, float elementSize) {
        float maximum = Math.max(MARGIN, viewport - MARGIN - elementSize);
        return Math.max(MARGIN, Math.min(maximum, value));
    }
}
