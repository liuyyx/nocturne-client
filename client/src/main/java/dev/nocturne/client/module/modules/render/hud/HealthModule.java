package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;

import java.util.Locale;
import java.util.function.Supplier;

/** 以心数显示本地玩家当前血量和吸收血量。 */
public final class HealthModule extends HudModule {
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            dev.nocturne.client.NocturneClient client = dev.nocturne.client.NocturneClient.get();
            PlayerState state = client == null || client.actions() == null
                    ? PlayerState.empty() : client.actions().readState();
            if (!state.present()) {
                return "";
            }
            String absorption = state.absorption() > 0.0f
                    ? String.format(Locale.ROOT, " +%.1f", state.absorption() / 2.0f) : "";
            return String.format(Locale.ROOT, "♥ %.1f%s", state.health() / 2.0f, absorption);
        }
    };

    @Override
    public String name() {
        return "Health";
    }

    @Override
    public Category category() {
        return Category.RENDER;
    }

    @Override
    protected String hudId() {
        return "health";
    }

    @Override
    protected Supplier<String> hudText() {
        return line;
    }
}
