package dev.nocturne.injector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 动画速度百分比 → 状态点过渡时长的换算契约。
 *
 * <p>100 为标准速度（约 150ms），200 翻倍，0 表示关闭动画（立即切换）。换算错了用户拖滑块
 * 也感受不到差别——这正是设置项「改了没反应」类问题的来源。
 */
class StatusDotTest {

    /** 默认即标准速度。 */
    @Test
    void defaultsToStandardSpeed() {
        assertEquals(150, new StatusDot().transitionDurationMs());
    }

    /** 200% 动画速度：过渡时长减半。 */
    @Test
    void doubleSpeedHalvesDuration() {
        StatusDot dot = new StatusDot();
        dot.setSpeedPercent(200);
        assertEquals(75, dot.transitionDurationMs());
    }

    /** 50% 动画速度：过渡时长翻倍。 */
    @Test
    void halfSpeedDoublesDuration() {
        StatusDot dot = new StatusDot();
        dot.setSpeedPercent(50);
        assertEquals(300, dot.transitionDurationMs());
    }

    /** 0 关闭动画：立即切换。 */
    @Test
    void zeroSpeedSwitchesInstantly() {
        StatusDot dot = new StatusDot();
        dot.setSpeedPercent(0);
        assertEquals(0, dot.transitionDurationMs());
    }

    /** 负数与非法值按 0 处理。 */
    @Test
    void negativeSpeedSwitchesInstantly() {
        StatusDot dot = new StatusDot();
        dot.setSpeedPercent(-20);
        assertEquals(0, dot.transitionDurationMs());
    }
}
