package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** NoRotate：保留位置包的坐标，但把服务端朝向覆盖为本地朝向。 */
public final class NoRotateModule extends GameModule {
    public NoRotateModule() {
        super("No Rotate", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, event -> {
            if (event.direction == PacketEvent.Direction.RECEIVE
                    && bridge().isInstance(event.packet, ClassType.CLIENTBOUND_PLAYER_POSITION)) {
                bridge().preserveLocalRotation(event.packet, state().yaw(), state().pitch());
            }
        });
    }
}
