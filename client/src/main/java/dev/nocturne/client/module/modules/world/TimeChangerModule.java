package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/** 每 tick 将客户端世界时间写为配置值。 */
public final class TimeChangerModule extends GameModule {
    private final NumberValue time = add(new NumberValue("Time", 6000, 0, 24000, 1000));

    public TimeChangerModule() {
        super("Time", Category.WORLD);
    }

    @Override
    public void onTick() {
        if (bridge() != null) {
            bridge().setDayTime(time.get().longValue());
        }
    }
}
