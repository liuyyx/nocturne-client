package dev.nocturne.client.module.modules.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：Xray 的「可见方块 = 0」自检阈值。
 *
 * <p>真机事故：剔除把整个世界剔没了（方块名/映射对不上时），世界全黑。自检必须在样本足够、
 * 且一个方块都没放行时触发自动关闭；样本不足的窗口（刚启用、切维度）不得误关。
 */
class XraySelfCheckTest {

    @Test
    void triggersOnlyWhenEnoughSamplesAndNothingVisible() {
        assertTrue(XrayModule.cullingExhausted(2000, 0));
        assertTrue(XrayModule.cullingExhausted(5000, 0));
    }

    @Test
    void doesNotTriggerWithTooFewSamples() {
        assertFalse(XrayModule.cullingExhausted(0, 0));
        assertFalse(XrayModule.cullingExhausted(1999, 0));
    }

    @Test
    void doesNotTriggerWhenSomethingIsVisible() {
        assertFalse(XrayModule.cullingExhausted(5000, 1));
    }
}
