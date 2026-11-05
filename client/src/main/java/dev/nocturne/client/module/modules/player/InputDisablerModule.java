package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** InputDisabler：容器包前后补空输入/最后输入，避免服务器沿用移动状态。 */
public final class InputDisablerModule extends GameModule {
    private boolean sending;

    public InputDisablerModule() {
        super("InputDisabler", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, event -> {
            if (sending || event.direction != PacketEvent.Direction.SEND
                    || !(bridge().isInstance(event.packet, ClassType.SERVERBOUND_CONTAINER_CLICK)
                    || bridge().isInstance(event.packet, ClassType.SERVERBOUND_CONTAINER_CLOSE))) {
                return;
            }
            Object empty = bridge().inputPacket(true);
            Object restore = bridge().inputPacket(false);
            if (empty == null || restore == null) {
                return;
            }
            event.cancelled = true;
            sending = true;
            try {
                actions().sendPacket(empty);
                actions().sendPacket(event.packet);
                actions().sendPacket(restore);
            } finally {
                sending = false;
            }
        });
    }

    @Override
    protected void onStop() {
        sending = false;
    }
}
