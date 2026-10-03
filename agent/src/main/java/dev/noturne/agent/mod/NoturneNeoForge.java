package dev.noturne.agent.mod;

import dev.noturne.client.NoturneClient;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry point. Discovered through the {@code @Mod} annotation.
 */
@Mod("noturne")
public final class NoturneNeoForge {

    public NoturneNeoForge() {
        NoturneClient.boot(null);
    }
}
