package dev.noturne.agent.mod;

import dev.noturne.client.NoturneClient;

import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point (1.13+ FML). Discovered through the {@code @Mod} annotation.
 */
@Mod("noturne")
public final class NoturneForge {

    public NoturneForge() {
        NoturneClient.boot(null);
    }
}
