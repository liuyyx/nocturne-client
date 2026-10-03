package dev.noturne.client.module;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.hud.HudSink;

import java.util.function.Supplier;

/**
 * A module whose only effect is a HUD line: arming it publishes the line, disarming retracts it.
 */
public abstract class HudModule extends Module {

    /** Stable id used by the HUD sink; must be unique across modules. */
    protected abstract String hudId();

    /** Pulled every frame; return {@code null} or empty to draw nothing this frame. */
    protected abstract Supplier<String> hudText();

    @Override
    protected void onEnable() {
        HudSink sink = sink();
        if (sink != null) {
            sink.add(hudId(), hudText());
        }
    }

    @Override
    protected void onDisable() {
        HudSink sink = sink();
        if (sink != null) {
            sink.remove(hudId());
        }
    }

    private static HudSink sink() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.hudSink();
    }
}
