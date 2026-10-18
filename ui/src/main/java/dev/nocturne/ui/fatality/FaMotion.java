package dev.nocturne.ui.fatality;

/** 与帧率无关的指数逼近动画：收敛快、永不过冲。 */
final class FaMotion {
    /** 当前值。 */
    private float value;

    FaMotion(float initial) {
        value = initial;
    }

    /**
     * 朝目标推进一帧。
     *
     * @param target 目标值
     * @param dt     本帧时长（秒）
     * @param speed  每秒把差距缩小 e 倍的次数（越大越快）
     * @return 推进后的当前值
     */
    float toward(float target, float dt, float speed) {
        float t = 1.0f - (float) Math.exp(-Math.max(0.0f, dt) * speed);
        value += (target - value) * t;
        if (Math.abs(target - value) < 0.001f) {
            value = target;
        }
        return value;
    }

    /** @return 当前值 */
    float value() {
        return value;
    }

    /** 直接置值，跳过动画。 */
    void snap(float value) {
        this.value = value;
    }
}
