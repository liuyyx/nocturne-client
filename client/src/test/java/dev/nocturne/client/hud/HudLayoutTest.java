package dev.nocturne.client.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 纯数值布局测试：九锚点、间距和视口边缘均不依赖绘制后端。 */
class HudLayoutTest {
    @Test
    void allNineAnchorsPlaceASingleElement() {
        HudAnchor[] anchors = {
                HudAnchor.TOP_LEFT, HudAnchor.TOP_CENTER, HudAnchor.TOP_RIGHT,
                HudAnchor.CENTER_LEFT, HudAnchor.CENTER, HudAnchor.CENTER_RIGHT,
                HudAnchor.BOTTOM_LEFT, HudAnchor.BOTTOM_CENTER, HudAnchor.BOTTOM_RIGHT
        };
        float[] expectedX = {6f, 140f, 274f, 6f, 140f, 274f, 6f, 140f, 274f};
        float[] expectedY = {6f, 6f, 6f, 110f, 110f, 110f, 214f, 214f, 214f};
        assertEquals(HudAnchor.values().length, anchors.length);
        for (int i = 0; i < anchors.length; i++) {
            assertEquals(expectedX[i], HudLayout.x(anchors[i], 320f, 40f), 0.001f,
                    anchors[i].name());
            assertEquals(expectedY[i], HudLayout.y(anchors[i], 240f, 20f, 0f, 20f),
                    0.001f, anchors[i].name());
        }
    }

    @Test
    void stackUsesSixPixelMarginAndFourPixelGaps() {
        float[] heights = {10f, 20f, 15f};
        float total = 53f;
        float first = HudLayout.y(HudAnchor.TOP_LEFT, 240f, total, 0f, heights[0]);
        float second = HudLayout.y(HudAnchor.TOP_LEFT, 240f, total, 14f, heights[1]);
        float third = HudLayout.y(HudAnchor.TOP_LEFT, 240f, total, 38f, heights[2]);
        assertEquals(6f, first, 0.001f);
        assertEquals(4f, second - first - heights[0], 0.001f);
        assertEquals(4f, third - second - heights[1], 0.001f);
    }

    @Test
    void centeredStackCentersTheWholeGroupInsteadOfEveryElement() {
        float total = 34f;
        assertEquals(103f, HudLayout.y(HudAnchor.CENTER, 240f, total, 0f, 10f), 0.001f);
        assertEquals(117f, HudLayout.y(HudAnchor.CENTER, 240f, total, 14f, 20f), 0.001f);
    }


    @Test
    void bottomStackPlacesTheFirstElementNearestTheBottomEdge() {
        float total = 34f;
        for (HudAnchor anchor : new HudAnchor[]{
                HudAnchor.BOTTOM_LEFT, HudAnchor.BOTTOM_CENTER, HudAnchor.BOTTOM_RIGHT}) {
            float first = HudLayout.y(anchor, 240f, total, 0f, 10f);
            float second = HudLayout.y(anchor, 240f, total, 14f, 20f);
            assertEquals(224f, first, 0.001f);
            assertEquals(200f, second, 0.001f);
            assertEquals(4f, first - second - 20f, 0.001f);
        }
    }

    @Test
    void outOfBoundsOffsetsClampInsideTheViewportMargins() {
        for (HudAnchor anchor : HudAnchor.values()) {
            float x = HudLayout.x(anchor, 100f, 30f);
            assertTrue(x >= 6f && x + 30f <= 94f, anchor.name());
            boolean bottom = anchor == HudAnchor.BOTTOM_LEFT || anchor == HudAnchor.BOTTOM_CENTER
                    || anchor == HudAnchor.BOTTOM_RIGHT;
            assertEquals(bottom ? 64f : 6f,
                    HudLayout.y(anchor, 100f, 30f, -1000f, 30f), 0.001f);
            assertEquals(bottom ? 6f : 64f,
                    HudLayout.y(anchor, 100f, 30f, 1000f, 30f), 0.001f);
        }
    }

    @Test
    void oversizedElementsAndTinyViewportsStayAtTheLeadingMargin() {
        for (HudAnchor anchor : HudAnchor.values()) {
            assertEquals(6f, HudLayout.x(anchor, 20f, 100f), 0.001f);
            assertEquals(6f, HudLayout.y(anchor, 20f, 100f, 0f, 100f), 0.001f);
            assertEquals(6f, HudLayout.x(anchor, 0f, 10f), 0.001f);
            assertEquals(6f, HudLayout.y(anchor, 0f, 10f, 0f, 10f), 0.001f);
        }
    }
}
