package dev.noturne.client.module.modules;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;

/**
 * Respawns the moment the player dies instead of sitting on the death screen.
 *
 * <p>Reads {@code Entity.isDead} through the mapping and calls the client player's
 * {@code respawnPlayer()}; both are ordinary game calls, so this is indistinguishable from the
 * player clicking "Respawn".
 */
public final class AutoRespawnModule extends Module {

    @Override
    public String name() {
        return "AutoRespawn";
    }

    @Override
    public Category category() {
        return Category.PLAYER;
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object player = bridge.player();
        if (player == null) {
            return;
        }
        Object dead = bridge.readField(player, ClassType.ENTITY, "isDead");
        if (Boolean.TRUE.equals(dead)) {
            bridge.callMapped(player, ClassType.LOCAL_PLAYER, "respawnPlayer");
        }
    }

    private static GameBridge bridge() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
