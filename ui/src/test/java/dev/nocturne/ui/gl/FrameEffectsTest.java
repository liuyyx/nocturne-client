package dev.nocturne.ui.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 帧降采样与 MotionBlur 历史权重测试。 */
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
}
