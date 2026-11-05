package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** PacketEat：食物使用期间拦截释放包，避免服务端提前结束进食。 */
public final class PacketEatModule extends GameModule {
    public PacketEatModule() {
        super("Packet Eat", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, event -> {
            if (event.direction != PacketEvent.Direction.SEND || !bridge().isReleaseUseItem(event.packet)
                    || !bridge().usingItem(bridge().player())) {
                return;
            }
            String id = bridge().itemDescription(bridge().usingItemStack(bridge().player()));
            if (id.matches(".*(apple|bread|stew|soup|porkchop|beef|chicken|mutton|rabbit|cod|salmon|cookie|melon|carrot|potato|beetroot|berries|honey|rotten_flesh|spider_eye).*")) {
                event.cancelled = true;
            }
        });
    }
}
