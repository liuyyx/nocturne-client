package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/** 调整真实游戏 tick 速率，禁用时恢复 1.0。 */
public final class TimerModule extends GameModule {
    private final NumberValue speed = add(new NumberValue("Speed", 1.07, 0.1, 2.0, 0.01));

    public TimerModule() {
        super("Timer", Category.MISC);
    }

    @Override
    public void onTick() {
        if (bridge() != null) {
            bridge().setTimerSpeed(speed.asFloat());
        }
    }

    @Override
    protected void onStop() {
        if (bridge() != null) {
            bridge().setTimerSpeed(1.0f);
        }
    }

    public float speed() {
        return speed.asFloat();
    }
}
