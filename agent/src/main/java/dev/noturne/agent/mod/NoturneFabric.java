package dev.noturne.agent.mod;

import dev.noturne.client.NoturneClient;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entry point. Referenced by {@code fabric.mod.json}; the interface comes from the
 * loader at runtime, so this module only needs it at compile time.
 */
public final class NoturneFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NoturneClient.boot(null);
    }
}
