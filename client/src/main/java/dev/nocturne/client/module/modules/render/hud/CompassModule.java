package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;

import java.util.Locale;
import java.util.function.Supplier;

/** 按 Minecraft 朝向约定显示八向指南针和归一化 yaw。 */
public final class CompassModule extends HudModule {
    private static final String[] DIRECTIONS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            NocturneClient client = NocturneClient.get();
            ActionApi actions = client == null ? null : client.actions();
            PlayerState state = actions == null ? PlayerState.empty() : actions.readState();
            if (!state.present()) {
                return "";
            }
            float degrees = (state.yaw() % 360f + 360f) % 360f;
            int index = ((int) Math.floor((degrees + 22.5f) / 45f)) & 7;
            return String.format(Locale.ROOT, "%s %.1f°", DIRECTIONS[index], degrees);
        }
    };

    @Override
    public String name() {
        return "Compass";
    }

    @Override
    public Category category() {
        return Category.RENDER;
    }

    @Override
    protected String hudId() {
        return "compass";
    }

    @Override
    protected Supplier<String> hudText() {
        return line;
    }
}
