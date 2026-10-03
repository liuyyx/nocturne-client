package dev.noturne.client.game;

import dev.noturne.client.mapping.IdentityMapping;
import dev.noturne.client.mapping.ObfuscatedMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameBridgeTest {

    @Test
    void obfuscatedCandidatesPreferTheMappedName() {
        GameBridge bridge = new GameBridge(null, ObfuscatedMapping.load("/mappings-1.8.9.json"));
        assertArrayEquals(
                new String[]{"ave", "net.minecraft.client.Minecraft"},
                bridge.minecraftClassCandidates());
    }

    @Test
    void identityCandidatesAreJustTheCanonicalName() {
        GameBridge bridge = new GameBridge(null, new IdentityMapping());
        assertArrayEquals(
                new String[]{"net.minecraft.client.Minecraft"},
                bridge.minecraftClassCandidates());
    }

    @Test
    void unresolvedBridgeDegradesToNullInsteadOfThrowing() {
        GameBridge bridge = new GameBridge(null, new IdentityMapping());
        assertFalse(bridge.isResolved());
        assertFalse(bridge.resolve(), "no game on this classpath");
        assertNull(bridge.minecraft());
        assertNull(bridge.player());
        assertFalse(bridge.inWorld());
        assertNull(bridge.readField(null, dev.noturne.client.mapping.ClassType.MINECRAFT, "player"));
    }

    @Test
    void readsFieldsThroughTheMapping() {
        // A stand-in for the game class: field name comes from the mapping, not the code.
        GameBridge bridge = new GameBridge(null, new IdentityMapping());
        Object holder = new Holder();
        Object value = bridge.readField(holder, dev.noturne.client.mapping.ClassType.MINECRAFT, "player");
        assertTrue(value instanceof String);
        assertNull(bridge.readField(holder, dev.noturne.client.mapping.ClassType.MINECRAFT, "nothing"));
    }

    /** Mirrors a game object exposing a mapped field. */
    static final class Holder {
        @SuppressWarnings("unused")
        private final String player = "local-player";
    }
}
