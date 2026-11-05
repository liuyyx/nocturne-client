package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ModeValue;

/** 每 tick 写入客户端天气强度。 */
public final class WeatherModule extends GameModule {
    private final ModeValue weather = add(new ModeValue("Weather", "Clear", "Clear", "Rain", "Thunder"));

    public WeatherModule() {
        super("Weather", Category.WORLD);
    }

    @Override
    public void onTick() {
        if (bridge() != null) {
            bridge().setWeather(weather.is("Clear") ? 0.0f : 1.0f,
                    weather.is("Thunder") ? 1.0f : 0.0f);
        }
    }
}
