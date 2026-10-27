package dev.nocturne.ui.gl;

import dev.nocturne.client.mapping.IdentityMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 钉住游戏原生库存方法的签名、区域口径和失败语义；无需启动 GL/游戏。 */
class InventoryPlayerPreviewTest {
    static final class Player {
    }

    static final class Graphics {
        int submissions;
        int left, top, right, bottom, size;
        float offset, mouseX, mouseY;
        boolean fail;
        final int originalState = 17;
    }

    /** 26.x 与 1.20.2+：真实方法接受完整绘制区域和两轴鼠标位置。 */
    public static final class RegionScreen {
        public static void extractEntityInInventoryFollowsMouse(Graphics graphics,
                int left, int top, int right, int bottom, int size, float offset,
                float mouseX, float mouseY, Player player) {
            if (graphics.fail) {
                throw new IllegalStateException("native extraction failed");
            }
            graphics.submissions++;
            graphics.left = left;
            graphics.top = top;
            graphics.right = right;
            graphics.bottom = bottom;
            graphics.size = size;
            graphics.offset = offset;
            graphics.mouseX = mouseX;
            graphics.mouseY = mouseY;
        }
    }

    /** 1.20–1.20.1 的方法不接收矩形，而接收脚下锚点与鼠标偏移。 */
    public static final class AnchorScreen {
        public static void renderEntityInInventoryFollowsMouse(Graphics graphics,
                int x, int y, int size, float yaw, float pitch, Player player) {
            graphics.submissions++;
            graphics.left = x;
            graphics.top = y;
            graphics.size = size;
            graphics.mouseX = yaw;
            graphics.mouseY = pitch;
        }
    }

    @Test
    void nativeRegionReceivesModelBoxAndCursorWithoutChangingFrameState() {
        InventoryPlayerPreview preview = new InventoryPlayerPreview(new IdentityMapping(),
                RegionScreen.class, Graphics.class);
        Graphics frame = new Graphics();
        Player player = new Player();
        assertTrue(preview.draw(frame, player, 10, 20, 110, 170, 50, 60));
        assertEquals(1, frame.submissions);
        assertEquals(10, frame.left);
        assertEquals(20, frame.top);
        assertEquals(110, frame.right);
        assertEquals(170, frame.bottom);
        assertEquals(52, frame.size);
        assertEquals(0.0625f, frame.offset);
        assertEquals(50, frame.mouseX);
        assertEquals(60, frame.mouseY);
        assertEquals(17, frame.originalState);
        assertFalse(preview.draw(frame, null, 10, 20, 110, 170, 50, 60));
        assertFalse(preview.draw(frame, player, 10, 20, 10, 170, 50, 60));
        assertFalse(preview.draw(null, player, 10, 20, 110, 170, 50, 60));
        assertEquals(1, frame.submissions, "without a player/frame/visible area the game must receive nothing");
    }

    @Test
    void nativeFailureIsNotReportedAsRenderedAndNextFrameCanRecover() {
        InventoryPlayerPreview preview = new InventoryPlayerPreview(new IdentityMapping(),
                RegionScreen.class, Graphics.class);
        Graphics frame = new Graphics();
        frame.fail = true;
        assertFalse(preview.draw(frame, new Player(), 0, 0, 100, 140, 40, 55));
        assertEquals(0, frame.submissions);
        frame.fail = false;
        assertTrue(preview.draw(frame, new Player(), 0, 0, 100, 140, 40, 55));
        assertEquals(1, frame.submissions);
    }

    @Test
    void oldGuiGraphicsSignatureUsesFeetAnchorAndMouseOffsets() {
        InventoryPlayerPreview preview = new InventoryPlayerPreview(new IdentityMapping(),
                AnchorScreen.class, Graphics.class);
        Graphics frame = new Graphics();
        assertTrue(preview.draw(frame, new Player(), 10, 20, 110, 170, 60, 70));
        assertEquals(1, frame.submissions);
        assertEquals(60, frame.left);
        assertEquals(160, frame.top);
        assertEquals(52, frame.size);
        assertEquals(0, frame.mouseX);
        assertEquals(38, frame.mouseY);
    }
}
