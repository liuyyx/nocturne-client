package dev.nocturne.client.hud;

import dev.nocturne.client.render.OverlayDraw;

/** 一个可测量的自绘 HUD 元素；坐标均为逻辑像素。 */
public interface HudElement {
    /** 全局唯一标识，与所属模块名一致。 */
    String id();

    HudAnchor anchor();

    /** 同锚点内的堆叠顺序，小的更靠屏幕边缘。 */
    default int order() {
        return 0;
    }

    float width(OverlayDraw draw);

    float height(OverlayDraw draw);

    /** 在指定左上角绘制本元素。 */
    void draw(OverlayDraw draw, float x, float y);
}
