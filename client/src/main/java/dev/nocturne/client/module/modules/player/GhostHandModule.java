package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.UseItemEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** GhostHand：普通准星未指向容器时，沿视线查找并交互最近的方块实体。 */
public final class GhostHandModule extends GameModule {
    public GhostHandModule() {
        super("GhostHand", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(UseItemEvent.class, event -> {
            if (event.post || bridge().player() == null) {
                return;
            }
            double yaw = Math.toRadians(state().yaw());
            double pitch = Math.toRadians(state().pitch());
            double dx = -Math.sin(yaw) * Math.cos(pitch) * 0.1;
            double dy = -Math.sin(pitch) * 0.1;
            double dz = Math.cos(yaw) * Math.cos(pitch) * 0.1;
            for (int step = 1; step <= 45; step++) {
                int x = (int) Math.floor(state().x() + dx * step);
                int y = (int) Math.floor(state().y() + 1.62 + dy * step);
                int z = (int) Math.floor(state().z() + dz * step);
                Object pos = bridge().blockPos(x, y, z);
                Object blockEntity = bridge().callMapped(bridge().world(), ClassType.CLIENT_LEVEL,
                        "getBlockEntity", pos);
                if (blockEntity != null && actions().placeAt(x, y, z, "up")) {
                    actions().swing(false, false);
                    event.cancelled = true;
                    return;
                }
            }
        });
    }
}
