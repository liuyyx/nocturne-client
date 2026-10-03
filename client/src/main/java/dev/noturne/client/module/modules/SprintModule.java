package dev.noturne.client.module.modules;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.game.GameBridge;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;

/**
 * Keeps the player sprinting while armed.
 *
 * <p>Calls the real {@code Entity.setSprinting(boolean)} each tick, so the server sees ordinary
 * sprint packets rather than anything synthetic.
 */
public final class SprintModule extends Module {

    @Override
    public String name() {
        return "Sprint";
    }

    @Override
    public Category category() {
        return Category.MOVEMENT;
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
        bridge.callMapped(player, ClassType.ENTITY, "setSprinting", Boolean.TRUE);
    }

    private static GameBridge bridge() {
        NoturneClient client = NoturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
