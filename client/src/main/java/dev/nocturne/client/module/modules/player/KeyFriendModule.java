package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.control.Friends;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.KeyValue;

/** KeyFriend：鼠标中键把准星玩家加入或移出共享好友表。 */
public final class KeyFriendModule extends GameModule {
    private final KeyValue activate = add(new KeyValue("Activate Key", 2));

    public KeyFriendModule() {
        super("KeyFriend", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(MouseButtonEvent.class, event -> {
            if (!event.pressed || event.button != activate.get()) {
                return;
            }
            Object hit = actions().pick(5.0, 1.0f);
            Object entity = bridge().hitEntity(hit);
            if (entity != null && bridge().isInstance(entity, ClassType.PLAYER)) {
                boolean added = Friends.toggle(bridge().entityName(entity));
                System.out.println("[nocturne] " + bridge().entityName(entity)
                        + (added ? " added to friends" : " removed from friends"));
                event.cancelled = true;
            }
        });
    }
}
