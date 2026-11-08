package dev.nocturne.ui.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帧降采样/MotionBlur 历史权重，以及「黑帧」自检的回归。
 *
 * <p>真机事故：glReadPixels 在缓冲已交换/上下文错位时读回全黑，旧实现把这张黑帧以 ~63%
 * 不透明度贴回屏幕，结果整个世界几乎全黑、只有 HUD 与准星可见。
 */
class FrameEffectsTest {

    @Test
    void averagesPixelsAndAppliesHistoryWeight() {
        int[] source = {0xFF000000, 0xFF404040, 0xFF808080, 0xFFC0C0C0};
        int[] history = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
        int[] out = new int[1];
        FrameEffects.downsample(source, null, 2, 2, out, 1, 1, 0.65f);
        assertEquals(0xFF606060, out[0]);
        FrameEffects.downsample(source, history, 2, 2, out, 1, 1, 0.5f);
        assertEquals(0xFFAFAFAF, out[0]);
    }

    @Test
    void allBlackFrameIsRejected() {
        int[] frame = new int[64 * 64];
        java.util.Arrays.fill(frame, 0xFF000000);
        assertTrue(FrameEffects.tooDark(frame));
    }

    @Test
    void nearBlackFrameIsRejected() {
        int[] frame = new int[64 * 64];
        java.util.Arrays.fill(frame, 0xFF020202);
        assertTrue(FrameEffects.tooDark(frame));
    }

    @Test
    void normalFrameIsAccepted() {
        int[] frame = new int[64 * 64];
        java.util.Arrays.fill(frame, 0xFF7F7F7F);
        assertFalse(FrameEffects.tooDark(frame));
    }

    @Test
    void emptyOrNullFrameIsTreatedAsDark() {
        assertTrue(FrameEffects.tooDark(null));
        assertTrue(FrameEffects.tooDark(new int[0]));
    }
}
